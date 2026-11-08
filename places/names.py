"""Channel and class names of places/CONTRACT.md (no torch here: worker processes import it)."""
CHANNELS = ["air", "unseen", "water", "lava", "ground", "stone", "ore", "wood_natural", "plant",
            "built_solid", "door", "fence", "climb", "light", "storage", "other_solid"]
CLASSES = ["solid", "open_outside", "passage", "room", "cave_hall", "shaft", "junction", "built_structure"]
C_IN = len(CHANNELS)
K = len(CLASSES)
SIDE = 32
