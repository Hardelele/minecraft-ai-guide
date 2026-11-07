package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityEnderman;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;

/**
 * The crew's defence: one skill, used two ways. A watch (every half second) looks for hostile mobs (the world's own,
 * spawned by the game) within reach of the crew members who are out (not in the house) and the player; when there is
 * one and nobody guards yet, a guard card goes on the board for the best-armed agent that can be spared (free first,
 * else the one on the least important card, which goes back on the board). The guide may also plan a guard while the
 * others shelter (the shelter phase posts the same card). The guard stands between the mob and the others and hits
 * it as a player does: its hands (the fake player) with the weapon in hand (sword over axe over pickaxe, by the damage
 * the item gives), in a player's reach, about twice a second; the mob strikes back at the agent. At low health it
 * falls back into the house; when no threat is left for a few seconds it goes back to the work it left. What a kill
 * drops is picked up (loot, in the books as drops).
 */
class Guard {
    static final double R = 16;          // how near the crew a mob is a threat
    static final double REACH = 3.0;     // a player's hand
    static final float LOW = 8;          // health at which a guard falls back

    /** a hostile mob of the world, alive (an enderman only when it is angry at someone) */
    static boolean hostile(Entity e) {
        if (!(e instanceof IMob) || !(e instanceof EntityLiving) || e.isDead || ((EntityLivingBase) e).getHealth() <= 0) return false;
        if (e instanceof EntityEnderman) return ((EntityEnderman) e).getEntityToAttack() != null;
        return true;
    }

    /** who is to be kept safe: the crew members out of the house, and the player */
    static List<EntityLivingBase> exposed(Crew c, EntityPlayerMP p) {
        List<EntityLivingBase> out = new ArrayList<EntityLivingBase>();
        for (Agent a : c.agents) if (!a.isDead && !Crew.inside(a)) out.add(a);
        if (p != null && !p.capabilities.disableDamage) out.add(p);
        return out;
    }

    /** the threats to them: hostile mobs within R (and not far above or below), nearest first */
    @SuppressWarnings("unchecked")
    static List<EntityLiving> threats(Crew c, WorldServer w, EntityPlayerMP p) {
        List<EntityLiving> out = new ArrayList<EntityLiving>();
        final Map<EntityLiving, Double> d = new HashMap<EntityLiving, Double>();
        for (EntityLivingBase x : exposed(c, p))
            for (Object o : w.getEntitiesWithinAABB(EntityLiving.class, AxisAlignedBB.getBoundingBox(x.posX - R, x.posY - 8, x.posZ - R, x.posX + R, x.posY + 8, x.posZ + R))) {
                EntityLiving m = (EntityLiving) o;
                if (!hostile(m)) continue;
                double dist = m.getDistanceToEntity(x);
                if (dist > R || dist > 6 && !m.canEntityBeSeen(x)) continue;   // far, or behind rock (a cave below)
                if (!d.containsKey(m)) out.add(m);
                if (!d.containsKey(m) || d.get(m) > dist) d.put(m, dist);
            }
        java.util.Collections.sort(out, (a, b) -> Double.compare(d.get(a), d.get(b)));
        return out;
    }

    /** the best-armed agent that can be spared: not in the house, healthy, free first, else on the least important card */
    static Agent pick(Crew c, List<EntityLiving> near) {
        Agent best = null;
        double bs = -1e9;
        for (Agent a : c.agents) {
            if (a.isDead || a.getHealth() < LOW + 4 || a.commanded) continue;   // the player's units take the player's orders only
            TaskBoard.Card k = c.board.leased.get(a);
            if (k != null && k.kind.equals("guard")) return null;   // someone guards already
            double s = a.weapon() * 3 + (k == null ? 4 : -k.prio) - (near == null || near.isEmpty() ? 0 : a.getDistanceToEntity(near.get(0)) * 0.1);
            if (s > bs) { bs = s; best = a; }
        }
        return best;
    }

    static int lastWatch;

    /** every half second: a threat to the crew and nobody on guard: a guard card for the one picked */
    static void watch(Crew c, WorldServer w, EntityPlayerMP p) {
        if (w.getTotalWorldTime() % 10 != 0 || c.agents.isEmpty()) return;
        for (TaskBoard.Card k : c.board.cards) if (k.kind.equals("guard") && k.open()) return;   // a guard is on it (or planned)
        List<EntityLiving> t = threats(c, w, p);
        if (t.isEmpty()) return;
        Agent g = pick(c, t);
        if (g == null) return;
        EntityLiving m = t.get(0);
        Origin was = Crew.originNow;
        Crew.originNow = Origin.AUTO_DEFAULT;
        TaskBoard.Card k = c.board.post("guard", () -> new Fight((double[]) null)).pin(g).prio(9).work(20, 0, 0, 0, 0, 20 * 90);
        k.phase = null;   // the crew's own safety, not a phase's work
        Crew.originNow = was;
        c.board.assign(k, g, w, "goes to guard");
        Events.log("crew: " + g.getCustomNameTag() + " guards the crew: " + m.getCommandSenderName() + " at " + (int) m.posX + " " + (int) m.posY + " " + (int) m.posZ
                + " (" + t.size() + " near), weapon " + (g.bestWeapon() == null ? "hands" : Ledger.name(g.bestWeapon())) + ", health " + Math.round(g.getHealth()));
    }

    /** the attack modifiers of the item in the hands' grip (a fake player does not tick: they are put on here, as the game does) */
    static final Map<FakePlayer, ItemStack> gripped = new HashMap<FakePlayer, ItemStack>();

    static void grip(FakePlayer p, ItemStack held) {
        ItemStack was = gripped.get(p);
        if (was == held) return;
        if (was != null) p.getAttributeMap().removeAttributeModifiers(was.getAttributeModifiers());
        if (held != null) p.getAttributeMap().applyAttributeModifiers(held.getAttributeModifiers());
        gripped.put(p, held);
    }

    /**
     * Fight off the threats: post == null, until none is left for 3 s (then back to work); with a post (the planned
     * guard), stand there between the house and the night until the others are in, fighting what comes.
     */
    static class Fight implements Crew.Task {
        final double[] post;
        EntityLiving target;
        int ticks, cool, quiet, retreatTicks, kills;
        boolean retreat;
        Crew.Task loot, back;
        EntityLiving forced;   // one mob to fight (the player's attack order): until it is dead or gone
        boolean hold;          // the player's guard order: at its post until the order is taken back
        Fight(double[] post) { this.post = post; }
        Fight(double[] post, boolean hold) { this.post = post; this.hold = hold; }
        Fight(EntityLiving mob) { this.post = null; this.forced = mob; this.target = mob; }

        public Result step(Agent a, WorldServer w) {
            Crew c = Crew.instance;
            ticks++;
            if (cool > 0) cool--;
            if (retreat) {   // into the house (or the base), then the card ends: the watch picks another
                retreatTicks++;
                Result r = back.step(a, w);
                return r == Result.RUNNING && retreatTicks < 20 * 25 ? Result.RUNNING : Result.OK;
            }
            if (a.getHealth() < LOW) {
                retreat = true;
                int[] in = Crew.site != null ? new int[] { Crew.site[0] + 2, Crew.site[1], Crew.site[2] + 2 } : Ground.anchor(c, w);
                back = new Crew.GoTo(in[0] + 0.5, in[1], in[2] + 0.5, 1.0);
                Events.log("crew: " + a.getCustomNameTag() + " falls back to the house (health " + Math.round(a.getHealth()) + ")");
                Ledger.act(a, "fall back");
                return Result.RUNNING;
            }
            if (loot != null) {   // what the kill dropped
                if (loot.step(a, w) == Result.RUNNING) return Result.RUNNING;
                loot = null;
            }
            ItemStack weapon = a.bestWeapon();
            if (weapon != null && a.getHeldItem() != weapon) a.body.hold(weapon);
            if (target == null || !hostile(target) || ticks % 10 == 0) {
                if (target != null && target.isDead || target != null && target.getHealth() <= 0) {
                    kills++;
                    Ledger.act(a, "kill " + target.getCommandSenderName());
                    Events.log("crew: " + a.getCustomNameTag() + " killed " + target.getCommandSenderName());
                    loot = new Crew.Collect(target.posX, target.posY, target.posZ, 3);
                    target = null;
                    return Result.RUNNING;
                }
                if (forced != null) {   // its one mob: dead (looted above) or gone
                    if (kills > 0 || forced.isDead || a.getDistanceToEntity(forced) > 48) return Result.OK;
                    target = forced;
                } else {
                    List<EntityLiving> t = threats(c, w, c.playerIn(w));
                    target = t.isEmpty() ? null : t.get(0);
                }
            }
            if (forced != null && kills > 0 && loot == null) return Result.OK;
            if (target == null) {
                quiet++;
                if (post != null) {   // the planned guard: at its post until the others are in
                    boolean allIn = !hold;
                    for (Agent o : c.agents) if (o != a && !o.isDead && !Crew.inside(o)) allIn = false;
                    if (allIn) { Events.log("crew: " + a.getCustomNameTag() + " ends the guard: the others are in (kills " + kills + ")"); return Result.OK; }
                    if (Crew.dist(a, post[0], post[1], post[2]) > 1.5) {
                        if (ticks % 20 == 1) a.body.walkTo(post[0], post[1], post[2], 1.0);
                    } else if (Crew.site != null && !hold) Crew.face(a, post[0] * 2 - (Crew.site[0] + 2.5), post[1] + 1.5, post[2] * 2 - (Crew.site[2] + 2.5));   // looks out
                    return Result.RUNNING;
                }
                if (quiet > 60) { Events.log("crew: " + a.getCustomNameTag() + " ends the guard: no threat left (kills " + kills + ")"); return Result.OK; }
                return Result.RUNNING;
            }
            quiet = 0;
            double d = a.getDistanceToEntity(target);
            Crew.face(a, target.posX, target.posY + target.getEyeHeight() * 0.8, target.posZ);
            if (d > REACH - 0.4 || !a.canEntityBeSeen(target)) {   // closer: it walks at the mob (from the crew's side, where it was)
                if (ticks % 8 == 1 || a.body.pathless()) a.body.walkTo(target, 1.15);
                a.act((int) Math.floor(target.posX), (int) Math.floor(target.posY), (int) Math.floor(target.posZ), 4);
                return Result.RUNNING;
            }
            a.body.stop();
            a.act((int) Math.floor(target.posX), (int) Math.floor(target.posY), (int) Math.floor(target.posZ), 4);
            if (cool > 0) return Result.RUNNING;
            if (!a.body.facing(target.posX, target.posY + target.getEyeHeight() * 0.8, target.posZ, 25)) return Result.RUNNING;   // the eyes on it first
            cool = 11;   // about twice a second, as a player clicking (a mob is hurt at most every half second anyway)
            FakePlayer h = Skills.hands(a, w);
            ItemStack held = a.getHeldItem();
            grip(h, held);
            a.swingItem();
            h.attackTargetEntityWithCurrentItem(target);
            if (held != null && held.stackSize <= 0) {   // the weapon broke
                Ledger.flow(Ledger.Way.INGREDIENT, held, "worn out");
                a.bag.remove(held);
                a.body.hold(null);
                grip(h, null);
            }
            h.inventory.mainInventory[0] = null;
            // the hit was the agent's (its hands are its own): the mob strikes back at the agent, not at the hands
            target.setRevengeTarget(a);
            if (target.getAttackTarget() == h) target.setAttackTarget(a);
            if (target instanceof EntityCreature && ((EntityCreature) target).getEntityToAttack() == h) ((EntityCreature) target).setTarget(a);
            Ledger.act(a, "hit " + target.getCommandSenderName());
            return Result.RUNNING;
        }
    }
}
