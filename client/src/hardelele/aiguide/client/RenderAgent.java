package hardelele.aiguide.client;

import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.entity.RenderBiped;
import net.minecraft.entity.EntityLiving;
import net.minecraft.util.ResourceLocation;

/** a crew's helper drawn as a player: the player's model, its own skin, the item in its hand, its name over it */
public class RenderAgent extends RenderBiped {
    static final ResourceLocation[] SKINS = new ResourceLocation[6];

    static {
        for (int i = 1; i <= 5; i++) SKINS[i] = new ResourceLocation("aiguideclient", "textures/agents/agent" + i + ".png");
        SKINS[0] = SKINS[1];
    }

    public RenderAgent() {
        super(new ModelBiped(0.0F), 0.5F);
    }

    /** under its name: what its mind has it do ("думает…" while the model is asked) */
    @Override
    protected void passSpecialRender(net.minecraft.entity.EntityLivingBase e, double x, double y, double z) {
        super.passSpecialRender(e, x, y, z);
        String l = e instanceof Agent ? MindUi.label(((Agent) e).skin()) : null;
        if (l != null) renderLivingLabel(e, l, x, y - 0.27, z, 32);
    }

    @Override
    protected ResourceLocation getEntityTexture(EntityLiving e) {
        int n = e instanceof Agent ? ((Agent) e).skin() : 1;
        return SKINS[n >= 1 && n <= 5 ? n : 1];
    }
}
