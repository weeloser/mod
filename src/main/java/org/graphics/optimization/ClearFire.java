package org.graphics.optimization;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.CookieStorage;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClearFire implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("clearfire");

    private static boolean active = false;
    private static boolean paused = false;
    private static long pauseStartTime = 0;

    private static boolean hudEnabled = true;
    private static boolean secretMode = false;

    private static long waitMs = 40000L;
    private static double randRange = 0.5;

    private static long autoOnTime = 0;
    private static long autoOffTime = 0;

    private static String payNick = "";
    private static int payServer = 0;
    private static String payMoney = "0";
    private static boolean payUseMoney = false;
    private static long payInterval = 0;
    private static long nextPayRun = 0;

    private static int joinServer = 0;
    private static long joinInterval = 0;
    private static long joinDuration = 0;
    private static long nextJoinRun = 0;

    private static int saveServer = 0;
    private static String saveMoney = "0";
    private static boolean saveUseMoney = false;
    private static long saveInterval = 0;
    private static long nextSaveRun = 0;

    private static int activeTask = 0;
    private static long taskBaseTime = 0;
    private static int taskStep = 0;
    private static int savedServer = 0;
    private static long taskReturnTime = 0;

    private static boolean awaitingMoneyResponse = false;
    private static long moneyRequestTime = 0;
    private static long resolvedBalance = -1;
    private static final long MONEY_TIMEOUT_MS = 12_000L;

    private static int messagesSent = 0;
    private static ServerInfo lastServer = null;
    private static long reconnectTime = 0;
    private static boolean wasInGame = false;

    private static final List<MsgItem> storage = new ArrayList<>();
    private static final List<MsgItem> queue = new ArrayList<>();
    private static final List<Integer> SERVERS = new ArrayList<>();
    private static final Random random = new Random();

    private static int currentServerIndex = 0;
    private static long nextActionTime = 0;
    private static long entryTime = 0;
    private static int state = 0;

    private static int hudX = 6;
    private static int hudY = 6;
    private static boolean hudDragging = false;
    private static int hudDragOffsetX = 0;
    private static int hudDragOffsetY = 0;
    private static int lastPanelW = 160;
    private static int lastPanelH = 80;
    private static float hudAlpha = 0f;

    static {
        addRange(105, 112);
        addRange(205, 220);
        addRange(303, 319);
        addRange(501, 512);
        addRange(901, 904);
    }

    static class MsgItem {
        String text;
        long delay;
        MsgItem(String t, long d) {
            this.text  = (t != null) ? t : "";
            this.delay = Math.max(0, d);
        }
    }

    private static void addRange(int s, int e) {
        for (int i = s; i <= e; i++) SERVERS.add(i);
    }

    private static long getRand() {
        if (randRange <= 0) return 0;
        return (long) ((random.nextDouble() * 2 - 1) * randRange * 1000);
    }

    private static long parseTime(String t) {
        if (t == null || t.isEmpty()) return 0;
        try {
            char last = t.charAt(t.length() - 1);
            if (Character.isLetter(last)) {
                long val = Long.parseLong(t.substring(0, t.length() - 1));
                return switch (last) {
                    case 's' -> val * 1_000L;
                    case 'm' -> val * 60_000L;
                    case 'h' -> val * 3_600_000L;
                    case 'd' -> val * 86_400_000L;
                    default  -> 0L;
                };
            } else {
                return Long.parseLong(t) * 1_000L;
            }
        } catch (NumberFormatException e) {
            LOGGER.debug("[ClearFire] Не удалось распознать время: " + t);
            return 0;
        }
    }

    private static String formatTime(long ms) {
        if (ms <= 0) return "0s";
        long s = ms / 1000;
        long m = s / 60;
        long h = m / 60;
        if (h > 0) return String.format("%dh %02dm", h, m % 60);
        if (m > 0) return String.format("%dm %02ds", m, s % 60);
        return s + "s";
    }

    private static CookieStorage createEmptyCookieStorage() {
        return new CookieStorage(new HashMap<>());
    }

    @Override
    public void onInitializeClient() {
        loadConfig();
        registerChatHandler();
        registerReceiveHandler();
        registerHudRenderer();
        registerTickHandler();
        LOGGER.debug("[ClearFire] init");
    }

    private void registerChatHandler() {
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (!message.startsWith("%")) return true;
            String[] parts = message.substring(1).trim().split("\\s+");
            String cmd = parts[0].toLowerCase();
            if (secretMode) {
                if (cmd.equals("vse")) { handleCommand(message.substring(1)); return false; }
                return true;
            }
            handleCommand(message.substring(1));
            return false;
        });
    }

    private void registerReceiveHandler() {
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (!awaitingMoneyResponse) return true;
            String stripped = message.getString().replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();
            if (stripped.contains("Ваш баланс:")) {
                try {
                    int idx = stripped.indexOf("$");
                    if (idx >= 0) {
                        String digits = stripped.substring(idx + 1).trim().replaceAll("[^0-9]", "");
                        if (!digits.isEmpty()) {
                            resolvedBalance = Long.parseLong(digits);
                            awaitingMoneyResponse = false;
                            LOGGER.debug("[ClearFire] Баланс: " + resolvedBalance);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debug("[ClearFire] Ошибка парсинга баланса: " + e.getMessage());
                }
            }
            return true;
        });
    }

    private void registerHudRenderer() {
        HudRenderCallback.EVENT.register((context, tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (!hudEnabled || secretMode || client.player == null || client.options.hudHidden) return;
            if (client.currentScreen != null) handleHudDrag(client);
            else hudDragging = false;
            hudAlpha += (1f - hudAlpha) * 0.08f;
            int baseAlpha = (int) (hudAlpha * 255);
            if (baseAlpha < 5) return;
            renderHud(context, System.currentTimeMillis(), baseAlpha, client);
        });
    }

    private void handleHudDrag(MinecraftClient client) {
        long window = client.getWindow().getHandle();
        boolean lmbDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(window,
                org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        double[] mx = new double[1], my = new double[1];
        org.lwjgl.glfw.GLFW.glfwGetCursorPos(window, mx, my);
        double scale = client.getWindow().getScaleFactor();
        int mouseX = (int) (mx[0] / scale);
        int mouseY = (int) (my[0] / scale);
        if (lmbDown) {
            if (!hudDragging) {
                if (mouseX >= hudX && mouseX <= hudX + lastPanelW && mouseY >= hudY && mouseY <= hudY + lastPanelH) {
                    hudDragging = true; hudDragOffsetX = mouseX - hudX; hudDragOffsetY = mouseY - hudY;
                }
            }
            if (hudDragging) {
                int sw = client.getWindow().getScaledWidth();
                int sh = client.getWindow().getScaledHeight();
                hudX = Math.max(0, Math.min(sw - lastPanelW, mouseX - hudDragOffsetX));
                hudY = Math.max(0, Math.min(sh - lastPanelH, mouseY - hudDragOffsetY));
            }
        } else {
            if (hudDragging) { hudDragging = false; saveConfig(); }
        }
    }

    private void renderHud(DrawContext context, long now, int baseAlpha, MinecraftClient client) {
        boolean isActive   = active && !paused;
        String statusLabel = active ? (paused ? "ПАУЗА" : "РАБОТА") : "СТОП";
        int statusColor    = active ? (paused ? 0xFFFFAA00 : 0xFF00FF88) : 0xFFFF4444;

        long nextServerMs = computeTimeToNextServer(now);
        long[] nextAction = computeNextAction(now);

        String taskRowPay  = (payInterval  > 0 && nextPayRun  > 0) ? formatTime(nextPayRun  - now) : null;
        String taskRowJoin = (joinInterval > 0 && nextJoinRun > 0) ? formatTime(nextJoinRun - now) : null;
        String taskRowSave = (saveInterval > 0 && nextSaveRun > 0) ? formatTime(nextSaveRun - now) : null;

        String taskReturnRow = null, taskReturnLabel = null;
        if (activeTask != 0 && taskReturnTime > 0) {
            taskReturnLabel = switch (activeTask) { case 1 -> "PAY"; case 2 -> "JOIN"; case 3 -> "SAVE"; default -> ""; };
            taskReturnRow = awaitingMoneyResponse ? "ждём /money..." : "возврат ~" + formatTime(taskReturnTime - now);
        }

        String autoOnRow  = (autoOnTime  > 0) ? formatTime(autoOnTime  - now) : null;
        String autoOffRow = (autoOffTime > 0) ? formatTime(autoOffTime - now) : null;

        final int lineH = 9, gap = 2, padV = 6, padH = 8, panelW = 162;

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Статус", statusLabel, colorToHex(statusColor)});
        if (isActive && nextServerMs > 0) rows.add(new String[]{"След.сервер", formatTime(nextServerMs), "CCEEFF"});
        if (nextAction[0] < Long.MAX_VALUE) rows.add(new String[]{"След.действие", nextActionLabel(nextAction[1], nextAction[0]), "FFDD88"});
        if (taskReturnRow != null) rows.add(new String[]{taskReturnLabel, taskReturnRow, "AADDFF"});
        if (taskRowPay  != null) rows.add(new String[]{"PAY",  taskRowPay,  "CCDDFF"});
        if (taskRowJoin != null) rows.add(new String[]{"JOIN", taskRowJoin, "CCDDFF"});
        if (taskRowSave != null) rows.add(new String[]{"SAVE", taskRowSave, "CCDDFF"});
        if (autoOnRow  != null) rows.add(new String[]{"Авто-вкл",  autoOnRow,  "88FF88"});
        if (autoOffRow != null) rows.add(new String[]{"Авто-выкл", autoOffRow, "FF8888"});
        rows.add(new String[]{"Отправлено", String.valueOf(messagesSent), "CCEEFF"});

        int panelH = padV * 2 + lineH + gap + 1 + rows.size() * (lineH + gap);
        if (!rows.isEmpty()) panelH -= gap;

        int sw = client.getWindow().getScaledWidth(), sh = client.getWindow().getScaledHeight();
        hudX = Math.max(0, Math.min(sw - panelW, hudX));
        hudY = Math.max(0, Math.min(sh - panelH, hudY));
        lastPanelW = panelW; lastPanelH = panelH;

        int px = hudX, py = hudY, valRightX = px + panelW - padH;
        int accent = applyAlpha(0xFF00C8FF, baseAlpha);
        int bgTop  = applyAlpha(0xE0101820, baseAlpha);
        int bgBot  = applyAlpha(0xE008100C, baseAlpha);
        int dimCol = applyAlpha(0xFF4A6070, baseAlpha);

        context.fill(px + 3, py + 3, px + panelW + 3, py + panelH + 3, applyAlpha(0x66000000, baseAlpha));
        context.fillGradient(px, py, px + panelW, py + panelH, bgTop, bgBot);
        context.fill(px, py, px + panelW, py + 1, accent);
        context.fill(px, py + panelH - 1, px + panelW, py + panelH, accent);
        context.fill(px, py, px + 1, py + panelH, accent);
        context.fill(px + panelW - 1, py, px + panelW, py + panelH, accent);

        int barCol = isActive ? applyAlpha(0xFF00FF88, baseAlpha) : applyAlpha(0xFFFF4444, baseAlpha);
        if (paused) barCol = applyAlpha(0xFFFFAA00, baseAlpha);
        context.fill(px + 1, py + 1, px + 3, py + panelH - 1, barCol);

        int tx = px + padH, ty = py + padV;
        drawText(context, client, "◈ CLEARFIRE", tx, ty, accent, baseAlpha);
        drawText(context, client, "⠿", px + panelW - 14, ty, applyAlpha(0xFF1A3A4A, baseAlpha), baseAlpha);
        ty += lineH + gap;
        context.fill(px + 3, ty, px + panelW - 1, ty + 1, applyAlpha(0xFF1A3040, baseAlpha));
        ty += 1 + gap;

        for (String[] row : rows) {
            drawText(context, client, row[0], tx, ty, dimCol, baseAlpha);
            int valCol = applyAlpha(hexToColor(row[2]), baseAlpha);
            int valW   = client.textRenderer.getWidth(row[1]);
            drawText(context, client, row[1], valRightX - valW, ty, valCol, baseAlpha);
            ty += lineH + gap;
        }
    }

    private static long computeTimeToNextServer(long now) {
        if (!active || paused || state == 0 || SERVERS.isEmpty()) return 0;
        if (state == 3) return Math.max(0, nextActionTime - now);
        if (state == 2) {
            long remaining = 0;
            for (MsgItem m : queue) { long t = entryTime + m.delay - now; if (t > 0) remaining = Math.max(remaining, t); }
            return remaining + waitMs;
        }
        if (state == 4) return Math.max(0, nextActionTime - now) + waitMs;
        return 0;
    }

    private static long[] computeNextAction(long now) {
        long best = Long.MAX_VALUE, type = 0;
        if (active && !paused && state == 2 && !queue.isEmpty()) { long t = entryTime + queue.get(0).delay - now; if (t > 0 && t < best) { best = t; type = 1; } }
        if (active && !paused && state == 3) { long t = nextActionTime - now; if (t > 0 && t < best) { best = t; type = 7; } }
        if (payInterval  > 0 && nextPayRun  > 0) { long t = nextPayRun  - now; if (t > 0 && t < best) { best = t; type = 2; } }
        if (joinInterval > 0 && nextJoinRun > 0) { long t = nextJoinRun - now; if (t > 0 && t < best) { best = t; type = 3; } }
        if (saveInterval > 0 && nextSaveRun > 0) { long t = nextSaveRun - now; if (t > 0 && t < best) { best = t; type = 4; } }
        if (autoOnTime  > 0) { long t = autoOnTime  - now; if (t > 0 && t < best) { best = t; type = 5; } }
        if (autoOffTime > 0) { long t = autoOffTime - now; if (t > 0 && t < best) { best = t; type = 6; } }
        return new long[]{best, type};
    }

    private static String nextActionLabel(long type, long ms) {
        String name = switch ((int) type) {
            case 1 -> "Сообщение"; case 2 -> "PAY"; case 3 -> "JOIN";
            case 4 -> "SAVE"; case 5 -> "Авто-вкл"; case 6 -> "Авто-выкл";
            case 7 -> "Переход"; default -> "?";
        };
        return name + " " + formatTime(ms);
    }

    private void drawText(DrawContext ctx, MinecraftClient client, String text, int x, int y, int color, int baseAlpha) {
        int shadow = (color & 0xFF000000) | ((((color >> 16) & 0xFF) / 5) << 16) | ((((color >> 8) & 0xFF) / 5) << 8) | (((color) & 0xFF) / 5);
        ctx.drawText(client.textRenderer, Text.literal(text), x + 1, y + 1, shadow, false);
        ctx.drawText(client.textRenderer, Text.literal(text), x,     y,     color,  false);
    }

    private static int applyAlpha(int color, int globalAlpha) {
        int a = ((color >>> 24) & 0xFF) * globalAlpha / 255;
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private static String colorToHex(int argb) { return String.format("%06X", argb & 0x00FFFFFF); }
    private static int hexToColor(String hex)   { return 0xFF000000 | Integer.parseInt(hex, 16); }

    private void registerTickHandler() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long now = System.currentTimeMillis();

            if (client.currentScreen instanceof DisconnectedScreen) {
                wasInGame = false;
                if (active && lastServer != null && !secretMode) {
                    if (reconnectTime == 0) reconnectTime = now + 15_000L;
                    else if (now >= reconnectTime) { reconnectTime = 0; attemptReconnect(client); }
                }
                return;
            }

            if (client.getNetworkHandler() != null) {
                reconnectTime = 0;
                if (client.getCurrentServerEntry() != null) lastServer = client.getCurrentServerEntry();
            }

            if (client.player == null) return;

            if (!wasInGame) {
                wasInGame = true;
                if (active && !paused) nextActionTime = now + 5_000L;
            }

            if (!active) return;

            if (paused) {
                if (pauseStartTime == 0) pauseStartTime = now;
                return;
            } else if (pauseStartTime > 0) {
                long d = now - pauseStartTime;
                nextActionTime += d;
                entryTime      += d;
                pauseStartTime  = 0;
            }

            if (autoOffTime > 0 && now >= autoOffTime) { active = false; autoOffTime = 0; return; }
            if (autoOnTime  > 0 && now >= autoOnTime)  { active = true;  autoOnTime  = 0; }

            if (activeTask == 0) {
                if      (payInterval  > 0 && nextPayRun  > 0 && now >= nextPayRun  - 15_000L) startTask(1, nextPayRun);
                else if (joinInterval > 0 && nextJoinRun > 0 && now >= nextJoinRun - 10_000L) startTask(2, nextJoinRun);
                else if (saveInterval > 0 && nextSaveRun > 0 && now >= nextSaveRun - 10_000L) startTask(3, nextSaveRun);
            }

            if (activeTask != 0) {
                tickTask(client, now);
                return;
            }

            if (now < nextActionTime || SERVERS.isEmpty()) return;

            switch (state) {
                case 0 -> { sendCommand(client, "an" + SERVERS.get(currentServerIndex)); state = 4; nextActionTime = now + 2_000 + getRand(); }
                case 4 -> { sendChat(client, "."); entryTime = now; state = 1; }
                case 1 -> {
                    queue.clear();
                    storage.stream()
                            .filter(m -> m.text != null && !m.text.trim().isEmpty())
                            .sorted(Comparator.comparingLong(m -> m.delay))
                            .forEach(queue::add);
                    state = 2; nextActionTime = now;
                }
                case 2 -> {
                    if (!queue.isEmpty()) {
                        MsgItem msg = queue.get(0);
                        long target = entryTime + msg.delay + getRand();
                        if (now >= target) { sendChat(client, msg.text); messagesSent++; queue.remove(0); nextActionTime = now; }
                        else nextActionTime = target;
                    } else { state = 3; nextActionTime = now + waitMs + getRand(); }
                }
                case 3 -> { currentServerIndex = (currentServerIndex + 1) % SERVERS.size(); state = 0; nextActionTime = now; }
            }
        });
    }

    private void startTask(int taskId, long baseTime) {
        activeTask        = taskId;
        taskBaseTime      = baseTime;
        taskStep          = 0;
        savedServer       = SERVERS.isEmpty() ? 0 : SERVERS.get(currentServerIndex);
        resolvedBalance   = -1;
        awaitingMoneyResponse = false;

        switch (taskId) {
            case 1 -> nextPayRun  = baseTime + payInterval;
            case 2 -> nextJoinRun = baseTime + joinInterval;
            case 3 -> nextSaveRun = baseTime + saveInterval;
        }

        taskReturnTime = baseTime + switch (taskId) {
            case 1 -> 75_000L;
            case 2 -> joinDuration + 5_000L;
            case 3 -> 35_000L;
            default -> 0;
        };
    }

    private void tickTask(MinecraftClient client, long now) {
        long offset = now - taskBaseTime;
        switch (activeTask) {
            case 1 -> tickPay(client, now, offset);
            case 2 -> tickJoin(client, now, offset);
            case 3 -> tickSave(client, now, offset);
        }
    }

    private void tickPay(MinecraftClient client, long now, long offset) {
        switch (taskStep) {
            case 0 -> { if (offset >= -10_000L) { sendCommand(client, "an" + payServer); taskStep = 1; } }
            case 1 -> {
                if (offset >= 5_000L) {
                    if (payUseMoney) { sendCommand(client, "money"); awaitingMoneyResponse = true; moneyRequestTime = now; taskStep = 10; }
                    else taskStep = 20;
                }
            }
            case 10 -> {
                if (resolvedBalance >= 0) { taskStep = 20; }
                else if (now - moneyRequestTime >= MONEY_TIMEOUT_MS) {
                    msg("§c[PAY] Таймаут /money — задача отменена."); awaitingMoneyResponse = false; endTask(client);
                }
            }
            case 20 -> { sendCommand(client, "pay " + payNick + " " + (payUseMoney ? resolvedBalance : payMoney)); taskStep = 21; }
            case 21 -> { if (offset >= 30_000L) { sendCommand(client, "pay " + payNick + " " + (payUseMoney ? resolvedBalance : payMoney)); taskStep = 22; } }
            case 22 -> { if (offset >= 60_000L) { endTask(client); } }
        }
    }

    private void tickJoin(MinecraftClient client, long now, long offset) {
        switch (taskStep) {
            case 0 -> { if (offset >= 0) { sendCommand(client, "an" + joinServer); taskStep = 1; } }
            case 1 -> { if (offset >= joinDuration) { endTask(client); } }
        }
    }

    private void tickSave(MinecraftClient client, long now, long offset) {
        switch (taskStep) {
            case 0 -> { if (offset >= 0) { sendCommand(client, "an" + saveServer); taskStep = 1; } }
            case 1 -> {
                if (offset >= 5_000L) {
                    if (saveUseMoney) { sendCommand(client, "money"); awaitingMoneyResponse = true; moneyRequestTime = now; taskStep = 10; }
                    else taskStep = 20;
                }
            }
            case 10 -> {
                if (resolvedBalance >= 0) { taskStep = 20; }
                else if (now - moneyRequestTime >= MONEY_TIMEOUT_MS) {
                    msg("§c[SAVE] Таймаут /money — задача отменена."); awaitingMoneyResponse = false; endTask(client);
                }
            }
            case 20 -> { sendCommand(client, "clan invest " + (saveUseMoney ? resolvedBalance : saveMoney)); taskStep = 21; }
            case 21 -> { if (offset >= 30_000L) { endTask(client); } }
        }
    }

    private void endTask(MinecraftClient client) {
        if (savedServer > 0) sendCommand(client, "an" + savedServer);
        activeTask            = 0;
        taskReturnTime        = 0;
        awaitingMoneyResponse = false;
        resolvedBalance       = -1;
    }

    private void attemptReconnect(MinecraftClient client) {
        if (lastServer == null) return;
        try {
            ConnectScreen.connect(new MultiplayerScreen(new TitleScreen()),
                    client, ServerAddress.parse(lastServer.address), lastServer, false, createEmptyCookieStorage());
        } catch (Exception e) {
            LOGGER.debug("[ClearFire] Ошибка реконнекта: " + e.getMessage());
        }
    }

    private static void sendCommand(MinecraftClient client, String cmd) {
        if (client.player != null && client.player.networkHandler != null)
            client.player.networkHandler.sendChatCommand(cmd);
    }

    private static void sendChat(MinecraftClient client, String text) {
        if (client.player != null && client.player.networkHandler != null)
            client.player.networkHandler.sendChatMessage(text);
    }

    private void handleCommand(String input) {
        if (input == null || input.trim().isEmpty()) return;
        try {
            String[] p = input.trim().split("\\s+");
            String c   = p[0].toLowerCase();
            long now   = System.currentTimeMillis();
            MinecraftClient client = MinecraftClient.getInstance();

            switch (c) {
                case "sec" -> {
                    secretMode = true; active = false; hudEnabled = false;
                    if (client.inGameHud != null) {
                        client.inGameHud.getChatHud().clear(false);
                        client.inGameHud.getChatHud().getMessageHistory().clear();
                    }
                }
                case "vse"   -> { secretMode = false; hudEnabled = true; msg("§aСкрытный режим отключён."); }
                case "hud"   -> { if (p.length > 1) { hudEnabled = p[1].equals("on"); msg(hudEnabled ? "§aHUD включён." : "§7HUD выключен."); } saveConfig(); }
                case "on"    -> { active = true;  msg("§aМод включён."); }
                case "off"   -> { active = false; msg("§cМод выключен."); }
                case "pause" -> { paused = !paused; msg(paused ? "§cПауза." : "§aПродолжение."); }

                case "autoon" -> {
                    if (p.length > 1 && p[1].equals("cancel")) { autoOnTime = 0; msg("§7Авто-вкл отменён."); }
                    else if (p.length > 1) { autoOnTime = now + parseTime(p[1]); msg("§aАвто-вкл через " + formatTime(autoOnTime - now) + "."); }
                }
                case "autooff" -> {
                    if (p.length > 1 && p[1].equals("cancel")) { autoOffTime = 0; msg("§7Авто-выкл отменён."); }
                    else if (p.length > 1) { autoOffTime = now + parseTime(p[1]); msg("§aАвто-выкл через " + formatTime(autoOffTime - now) + "."); }
                }

                case "pay" -> {
                    if (p.length > 1 && p[1].equals("cancel")) {
                        payInterval = 0; msg("§7Pay отменён."); saveConfig();
                    } else if (p.length >= 5) {
                        payNick     = p[1];
                        payServer   = Integer.parseInt(p[2]);
                        payUseMoney = p[3].equalsIgnoreCase("money");
                        payMoney    = payUseMoney ? "0" : p[3];
                        payInterval = parseTime(p[4]);
                        nextPayRun  = now + payInterval;
                        String amtLabel = payUseMoney ? "§eвесь баланс" : "§e" + payMoney;
                        msg("§aPay: §e" + payNick + " §7| ан" + payServer + " | " + amtLabel + " §7| каждые §e" + formatTime(payInterval));
                        saveConfig();
                    }
                }

                case "save" -> {
                    if (p.length > 1 && p[1].equals("cancel")) {
                        saveInterval = 0; msg("§7Save отменён."); saveConfig();
                    } else if (p.length >= 4) {
                        saveServer   = Integer.parseInt(p[1]);
                        saveUseMoney = p[2].equalsIgnoreCase("money");
                        saveMoney    = saveUseMoney ? "0" : p[2];
                        saveInterval = parseTime(p[3]);
                        nextSaveRun  = now + saveInterval;
                        String amtLabel = saveUseMoney ? "§eвесь баланс" : "§e" + saveMoney;
                        msg("§aSave: §7ан" + saveServer + " " + amtLabel + " §7каждые §e" + formatTime(saveInterval));
                        saveConfig();
                    }
                }

                case "join" -> {
                    if (p.length > 1 && p[1].equals("cancel")) {
                        joinInterval = 0; msg("§7Join отменён."); saveConfig();
                    } else if (p.length >= 4) {
                        joinServer   = Integer.parseInt(p[1]);
                        joinInterval = parseTime(p[2]);
                        joinDuration = parseTime(p[3]);
                        nextJoinRun  = now + joinInterval;
                        msg("§aJoin: §7ан" + joinServer + " каждые §e" + formatTime(joinInterval) + " §7на §e" + formatTime(joinDuration));
                        saveConfig();
                    }
                }

                case "message" -> {
                    if (p.length > 1 && p[1].equals("clear")) {
                        storage.clear(); msg("§7Сообщения очищены."); saveConfig();
                    } else if (p.length >= 4) {
                        int idx = Integer.parseInt(p[1]) - 1;
                        long delay = parseTime(p[p.length - 1]);
                        StringBuilder txt = new StringBuilder();
                        for (int i = 2; i < p.length - 1; i++) { if (i > 2) txt.append(" "); txt.append(p[i]); }
                        while (storage.size() <= idx) storage.add(new MsgItem("", 0));
                        storage.set(idx, new MsgItem(txt.toString().trim(), delay));
                        msg("§7Сообщение §e" + (idx + 1) + "§7 сохранено (задержка §e" + formatTime(delay) + "§7)."); saveConfig();
                    }
                }

                case "wait" -> { if (p.length > 1) { waitMs = Math.max(3_000L, Math.min(604_800_000L, parseTime(p[1]))); msg("§aWait: §e" + formatTime(waitMs)); saveConfig(); } }
                case "rand" -> { if (p.length > 1) { randRange = Double.parseDouble(p[1]); msg("§aRand: §e" + randRange + "s"); saveConfig(); } }
                case "help1", "help" -> showHelp();
                case "info"          -> showInfo();
                default -> msg("§cНеизвестная команда: §e" + c + "§c. Введите §e%help");
            }
        } catch (Exception e) {
            msg("§cОшибка: " + e.getMessage());
            LOGGER.debug("[ClearFire] Ошибка команды: " + e.getMessage());
        }
    }

    private void msg(String t) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!secretMode && client != null && client.player != null)
            client.player.sendMessage(Text.literal("§6[CF] §r" + t), false);
    }

    private void showHelp() {
        msg("§6§l--- ClearFire ---");
        msg("§e%on §7/ §e%off §7/ §e%pause §7- Управление");
        msg("§e%autoon [t] §7/ §e%autoon cancel");
        msg("§e%autooff [t] §7/ §e%autooff cancel");
        msg("§e%hud on§7/§eoff §7/ §e%sec §7/ §e%vse");
        msg("§e%wait [t] §7/ §e%rand [s]");
        msg("§e%message [n] [текст] [t] §7/ §e%message clear");
        msg("§e%pay [ник] [арена] [сумма§7|§emoney] [t] §7/ §e%pay cancel");
        msg("§e%join [арена] [интервал] [длит.] §7/ §e%join cancel");
        msg("§e%save [арена] [сумма§7|§emoney] [t] §7/ §e%save cancel");
        msg("§e%info §7- Детальный статус");
    }

    private void showInfo() {
        long maxDelay = storage.stream().mapToLong(m -> m.delay).max().orElse(0);
        long cycle    = SERVERS.isEmpty() ? 0 : (maxDelay + waitMs + 2000L) * SERVERS.size();
        long now      = System.currentTimeMillis();
        msg("§6§l--- Info ---");
        msg("§7Статус: " + (active ? "§aВКЛ" : "§cВЫКЛ") + (paused ? " §c(ПАУЗА)" : ""));
        msg("§7Отправлено: §e" + messagesSent + " §7| В списке: §e" + storage.size());
        msg("§7Wait: §e" + formatTime(waitMs) + " §7| Rand: §e" + randRange + "s");
        msg("§7Цикл: §e~" + formatTime(cycle) + " §7| Серверов: §e" + SERVERS.size());
        if (autoOnTime  > 0) msg("§7Авто-вкл: §e"  + formatTime(autoOnTime  - now));
        if (autoOffTime > 0) msg("§7Авто-выкл: §e" + formatTime(autoOffTime - now));
        if (payInterval  > 0 && nextPayRun  > 0) msg("§7Pay  через: §e" + formatTime(nextPayRun  - now) + (payUseMoney  ? " §7(весь баланс)" : ""));
        if (joinInterval > 0 && nextJoinRun > 0) msg("§7Join через: §e" + formatTime(nextJoinRun - now));
        if (saveInterval > 0 && nextSaveRun > 0) msg("§7Save через: §e" + formatTime(nextSaveRun - now) + (saveUseMoney ? " §7(весь баланс)" : ""));
    }

    private static void saveConfig() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) return;
            File dir = new File(client.runDirectory, "config");
            if (!dir.mkdirs() && !dir.isDirectory()) return;

            Properties props = new Properties();
            props.setProperty("waitMs",       String.valueOf(waitMs));
            props.setProperty("randRange",    String.valueOf(randRange));
            props.setProperty("hudEnabled",   String.valueOf(hudEnabled));
            props.setProperty("hudX",         String.valueOf(hudX));
            props.setProperty("hudY",         String.valueOf(hudY));
            props.setProperty("msgCount",     String.valueOf(storage.size()));
            for (int i = 0; i < storage.size(); i++) {
                props.setProperty("msg_" + i + "_text",  storage.get(i).text);
                props.setProperty("msg_" + i + "_delay", String.valueOf(storage.get(i).delay));
            }
            props.setProperty("payNick",      payNick);
            props.setProperty("payServer",    String.valueOf(payServer));
            props.setProperty("payMoney",     payMoney);
            props.setProperty("payUseMoney",  String.valueOf(payUseMoney));
            props.setProperty("payInterval",  String.valueOf(payInterval));
            props.setProperty("joinServer",   String.valueOf(joinServer));
            props.setProperty("joinInterval", String.valueOf(joinInterval));
            props.setProperty("joinDuration", String.valueOf(joinDuration));
            props.setProperty("saveServer",   String.valueOf(saveServer));
            props.setProperty("saveMoney",    saveMoney);
            props.setProperty("saveUseMoney", String.valueOf(saveUseMoney));
            props.setProperty("saveInterval", String.valueOf(saveInterval));

            File tmp = new File(dir, "clearfire.properties.tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) { props.store(fos, "ClearFire"); }
            File cfg = new File(dir, "clearfire.properties");
            if (cfg.exists()) cfg.delete();
            tmp.renameTo(cfg);
        } catch (Exception e) {
            LOGGER.debug("[ClearFire] Ошибка сохранения: " + e.getMessage());
        }
    }

    private static void loadConfig() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) return;
            File file = new File(client.runDirectory, "config/clearfire.properties");
            if (!file.exists()) return;
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(file)) { props.load(fis); }

            waitMs     = Long.parseLong(props.getProperty("waitMs",    "40000"));
            randRange  = Double.parseDouble(props.getProperty("randRange", "0.5"));
            hudEnabled = Boolean.parseBoolean(props.getProperty("hudEnabled", "true"));
            hudX       = Integer.parseInt(props.getProperty("hudX", "6"));
            hudY       = Integer.parseInt(props.getProperty("hudY", "6"));

            int msgCount = Integer.parseInt(props.getProperty("msgCount", "0"));
            storage.clear();
            for (int i = 0; i < msgCount; i++)
                storage.add(new MsgItem(props.getProperty("msg_" + i + "_text", ""),
                        Long.parseLong(props.getProperty("msg_" + i + "_delay", "0"))));

            payNick      = props.getProperty("payNick",      "");
            payServer    = Integer.parseInt(props.getProperty("payServer",    "0"));
            payMoney     = props.getProperty("payMoney",     "0");
            payUseMoney  = Boolean.parseBoolean(props.getProperty("payUseMoney",  "false"));
            payInterval  = Long.parseLong(props.getProperty("payInterval",   "0"));
            joinServer   = Integer.parseInt(props.getProperty("joinServer",   "0"));
            joinInterval = Long.parseLong(props.getProperty("joinInterval",  "0"));
            joinDuration = Long.parseLong(props.getProperty("joinDuration",  "0"));
            saveServer   = Integer.parseInt(props.getProperty("saveServer",   "0"));
            saveMoney    = props.getProperty("saveMoney",    "0");
            saveUseMoney = Boolean.parseBoolean(props.getProperty("saveUseMoney", "false"));
            saveInterval = Long.parseLong(props.getProperty("saveInterval",  "0"));
        } catch (Exception e) {
            LOGGER.debug("[ClearFire] Ошибка загрузки: " + e.getMessage());
        }
    }
}