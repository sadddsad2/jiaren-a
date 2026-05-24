package com.example.fakeplayer.util;

import java.util.*;

public final class NameGenerator {

    private static final Random RANDOM = new Random();

    private static final List<String> PREFIXES = Arrays.asList(
            "Dark", "Swift", "Iron", "Stone", "Fire", "Ice", "Wild", "Brave",
            "Ghost", "Storm", "Shadow", "Gold", "Silver", "Blaze", "Frost",
            "Void", "Light", "Night", "Dawn", "Oak", "Red", "Blue", "Crystal",
            "Dragon", "Wolf", "Sky", "Sea", "Sand", "Ember", "Thorn"
    );

    private static final List<String> SUFFIXES = Arrays.asList(
            "Hunter", "Walker", "Runner", "Slayer", "Knight", "Miner", "Builder",
            "Caster", "Seeker", "Rider", "Striker", "Guard", "Scout", "Ranger",
            "Blade", "Arrow", "Shield", "Hammer", "Axe", "Craft", "Smith",
            "Forge", "Stone", "Wood", "Wind", "Star", "Moon", "Hawk", "Fox", "Bear"
    );

    private NameGenerator() {}

    public static String generate(Set<String> existing) {
        String name;
        int attempts = 0;
        do { name = build(); attempts++; }
        while (existing.contains(name) && attempts < 200);
        return name;
    }

    private static String build() {
        String prefix = PREFIXES.get(RANDOM.nextInt(PREFIXES.size()));
        String suffix = SUFFIXES.get(RANDOM.nextInt(SUFFIXES.size()));
        String name   = prefix + suffix;
        if (RANDOM.nextBoolean()) name += (RANDOM.nextInt(99) + 1);
        return name.length() > 16 ? name.substring(0, 16) : name;
    }
}
