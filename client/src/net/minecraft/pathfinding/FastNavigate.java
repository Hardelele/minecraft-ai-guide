package net.minecraft.pathfinding;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.world.World;

/**
 * A mob's PathNavigate whose searches go through FastPath (the game's path finder step for step, the same path): put
 * in the place of the game's at a living entity's joining the world when the learning engine is on (RlEngine), its
 * state copied field by field from the navigator it replaces, so the mobs' and animals' own AI (EntityAI*: attack,
 * wander, panic, tempt) walks the same paths, found quicker. Everything else is the game's (following a path, the sun).
 */
public class FastNavigate extends PathNavigate {
    final EntityLiving owner;

    FastNavigate(EntityLiving e, World w) {
        super(e, w);
        owner = e;
    }

    @Override
    public PathEntity getPathToXYZ(double x, double y, double z) { return FastPath.pathToXYZ(owner, x, y, z); }

    @Override
    public PathEntity getPathToEntityLiving(Entity target) { return FastPath.pathToEntity(owner, target); }

    static Field navigator;

    /** the entity's navigator replaced by a FastNavigate in the same state; false if it is one already or cannot be */
    public static boolean install(EntityLiving e) {
        try {
            if (navigator == null) {
                Field f;
                try { f = EntityLiving.class.getDeclaredField("field_70699_by"); } catch (NoSuchFieldException x) { f = EntityLiving.class.getDeclaredField("navigator"); }
                f.setAccessible(true);
                navigator = f;
            }
            Object old = navigator.get(e);
            if (old == null || old.getClass() != PathNavigate.class) return false;   // (one of its own kind, or a mod's: left alone)
            FastNavigate n = new FastNavigate(e, e.worldObj);
            for (Field f : PathNavigate.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                f.set(n, f.get(old));
            }
            navigator.set(e, n);
            return true;
        } catch (Exception x) {
            return false;
        }
    }
}
