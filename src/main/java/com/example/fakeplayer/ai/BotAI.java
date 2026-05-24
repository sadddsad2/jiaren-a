package com.example.fakeplayer.ai;

import com.example.fakeplayer.FakePlayerPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class BotAI {

    private static final Random RANDOM = new Random();

    // Weighted pool – more IDLEs = calmer bots
    private static final List<BotAction> POOL = Arrays.asList(
            BotAction.WALK_RANDOM, BotAction.WALK_RANDOM, BotAction.WALK_RANDOM,
            BotAction.JUMP,
            BotAction.LOOK_AROUND, BotAction.LOOK_AROUND,
            BotAction.SWING_ARM,
            BotAction.SNEAK_TOGGLE,
            BotAction.SPRINT_BURST,
            BotAction.CHAT_MESSAGE,
            BotAction.IDLE, BotAction.IDLE, BotAction.IDLE, BotAction.IDLE
    );

    private static final List<String> CHAT = Arrays.asList(
            "Hello everyone!", "Anyone want to trade?", "Let's mine together!",
            "Nice server!", "What's happening here?", "Ready to play!", "GG",
            "I love Minecraft!", "Where is everyone?", "This place is awesome!"
    );

    private final FakePlayerPlugin plugin;
    private boolean sneaking = false;

    public BotAI(FakePlayerPlugin plugin) {
        this.plugin = plugin;
    }

    public void tick(Player player) {
        if (!player.isOnline() || player.isDead()) return;
        BotAction action = POOL.get(RANDOM.nextInt(POOL.size()));
        switch (action) {
            case WALK_RANDOM  -> walkRandom(player);
            case JUMP         -> jump(player);
            case LOOK_AROUND  -> lookAround(player);
            case SWING_ARM    -> player.swingMainHand();
            case SNEAK_TOGGLE -> { sneaking = !sneaking; player.setSneaking(sneaking); }
            case SPRINT_BURST -> sprintBurst(player);
            case CHAT_MESSAGE -> { if (RANDOM.nextInt(5) == 0) player.chat(CHAT.get(RANDOM.nextInt(CHAT.size()))); }
            case IDLE         -> { /* no-op */ }
        }
    }

    private void walkRandom(Player player) {
        Location loc   = player.getLocation();
        double angle   = RANDOM.nextDouble() * 2 * Math.PI;
        double speed   = 0.15 + RANDOM.nextDouble() * 0.25;
        Location target = loc.clone().add(Math.cos(angle) * speed, 0, Math.sin(angle) * speed);
        if (target.getBlock().isPassable()
                && target.clone().subtract(0, 1, 0).getBlock().isSolid()) {
            player.teleport(target);
        }
    }

    private void jump(Player player) {
        if (player.isOnGround()) player.setVelocity(player.getVelocity().setY(0.42));
    }

    private void lookAround(Player player) {
        Location loc = player.getLocation();
        loc.setYaw(RANDOM.nextFloat() * 360 - 180);
        loc.setPitch(RANDOM.nextFloat() * 60 - 30);
        player.teleport(loc);
    }

    private void sprintBurst(Player player) {
        player.setSprinting(true);
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> { if (player.isOnline()) player.setSprinting(false); }, 20L);
    }
}
