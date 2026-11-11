"""The update as one CUDA graph (docs/rl/OPEN-WORLD.md section 10): the world model's pass over a sequence, the
imagination, the actor, the critic, the four optimizers, all recorded once and replayed with one launch. The update was
bound by the host's launches of small kernels (a recurrence of 32 steps, each a dozen kernels, forward and back, under
Python and Windows: 0.3-0.6 s of CPU for a few milliseconds of GPU); replayed from a graph the host does almost nothing
and the GPU runs back to back. torch.compile would fuse them too, but there is no triton under Windows.

The shapes are static (b1 + b2 rows of T steps: the agent's, the teacher's behind them); the data goes into static
tensors before each replay; the weight of behaviour cloning is a tensor too. Metrics are tensors the graph fills: read
them (metrics()) only now and then, a read waits for the GPU.
"""
from __future__ import annotations

import numpy as np
import torch


class FastUpdate:
    def __init__(self, agent, b1, b2, seq, bf16=True, train_ac=True):
        self.agent, self.b1, self.b2, self.seq, self.train_ac = agent, b1, b2, seq, train_ac
        agent.bf16 = bf16
        torch.backends.cudnn.benchmark = True
        self.dev = agent.device
        self.bcw = torch.zeros((), device=self.dev)
        self.static = None
        self.graph = None
        self.out = None
        self.n = 0
        self.stage = None          # two pinned host buffers taken turns: the host fills one while the GPU runs on the other's copy
        self.cur = 0
        self.ev = [None, None]

    def _concat(self, a, t):
        return {k: np.concatenate([a[k], t[k]], 0) for k in a}

    def _capture(self):
        ag = self.agent
        opts = (ag.opt_wm, ag.opt_ens, ag.opt_actor, ag.opt_critic)
        bc = (self.b1, None) if self.b2 else None
        torch.cuda.synchronize()
        side = torch.cuda.Stream()
        side.wait_stream(torch.cuda.current_stream())
        with torch.cuda.stream(side):   # (a few steps outside the graph: the optimizers' state, cudnn's choices, the allocator)
            for _ in range(3):
                ag.step(self.static, self.b1, bc, self.bcw, self.train_ac)
        torch.cuda.current_stream().wait_stream(side)
        torch.cuda.synchronize()
        self.graph = torch.cuda.CUDAGraph()
        for o in opts:
            o.zero_grad(set_to_none=True)
        with torch.cuda.graph(self.graph):
            self.out = ag.step(self.static, self.b1, bc, self.bcw, self.train_ac)

    def update(self, agent_batch, teacher_batch, bc_weight):
        """one update: agent_batch (b1 rows) and teacher_batch (b2 rows) from Replay.sample"""
        batch = self._concat(agent_batch, teacher_batch) if self.b2 else agent_batch
        if self.static is None:
            self.static = {k: torch.as_tensor(v, device=self.dev).clone() for k, v in batch.items()}
            self.stage = [{k: torch.empty(v.shape, dtype=self.static[k].dtype).pin_memory() for k, v in batch.items()} for _ in range(2)]
            self.bcw.fill_(bc_weight)
            self._capture()
        else:
            if self.ev[self.cur] is not None:
                self.ev[self.cur].synchronize()   # the copy from this buffer two updates ago is done
            buf = self.stage[self.cur]
            for k, v in batch.items():
                np.copyto(buf[k].numpy(), v, casting="unsafe")
                self.static[k].copy_(buf[k], non_blocking=True)   # (queued behind the replay in flight: the host does not wait)
        self.bcw.fill_(bc_weight)
        self.graph.replay()
        self.ev[self.cur] = torch.cuda.Event()
        self.ev[self.cur].record()
        self.cur ^= 1
        self.n += 1

    def metrics(self):
        """what the last replay measured, as floats (waits for the GPU)"""
        return {k: float(v) for k, v in self.out.items()} if self.out else {}
