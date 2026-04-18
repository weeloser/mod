package org.graphics.optimization;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ClearFire implements ClientModInitializer {
    private static boolean active = false;
    private static int d1 = 180, d2 = 3, d3 = 40;
    private static double randRange = 0.8;

    private static int saveServer = 0;
    private static long saveInterval = 0, nextSaveTime = 0;
    private static boolean waitingForMoney = false;
    private static long moneyRequestTime = 0;

    private static String payNick = "";
    private static int payServer = 0;
    private static LocalTime payTime = null;
    private static boolean payPending = false;

    private static int joinServer = 0;
    private static LocalTime joinTime = null;
    private static long joinDuration = 0, resumeTime = 0;
    private static boolean joinPending = false;

    private static final List<String> storage = new ArrayList<>();
    private static final List<String> queue = new ArrayList<>();
    private static final List<Integer> SERVERS = new ArrayList<>();
    private static final Random random = new Random();

    private static int currentServerIndex = 0;
    private static long nextActionTime = 0;
    private static int state = 0;
    private static boolean messageConfirmed = true;

    static {
        addRange(105, 112); addRange(205, 220); addRange(303, 319);
        addRange(501, 512); addRange(901, 904);
    }

    private static void addRange(int start, int end) {
        for (int i = start; i <= end; i++) SERVERS.add(i);
    }

    private static long getRandOffset() {
        return (randRange <= 0) ? 0 : (long) ((random.nextDouble() * 2 - 1) * randRange * 1000);
    }

    @Override
    public void onInitializeClient() {
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (message.startsWith("%")) { handleSilentCommand(message.substring(1)); return false; }
            if (active && state == 2 && !queue.isEmpty() && message.equals(queue.get(0))) messageConfirmed = true;
            return true;
        });

        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            if (command.startsWith("%")) { handleSilentCommand(command.substring(1)); return false; }
            return true;
        });

        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            String text = message.getString();
            if (waitingForMoney && text.contains("Ваш баланс:")) {
                String cleanMoney = text.replaceAll("[^0-9]", "");
                if (!cleanMoney.isEmpty()) {
                    if (!payNick.isEmpty()) {
                        String cmd = "pay " + payNick + " " + cleanMoney;
                        MinecraftClient.getInstance().player.networkHandler.sendChatCommand(cmd);
                        MinecraftClient.getInstance().player.networkHandler.sendChatCommand(cmd);
                        payNick = "";
                    } else {
                        MinecraftClient.getInstance().player.networkHandler.sendChatCommand("clan invest " + cleanMoney);
                    }
                    waitingForMoney = false;
                }
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!active || client.player == null) return;
            long now = System.currentTimeMillis();
            LocalTime timeNow = LocalTime.now();

            if (resumeTime > 0) {
                if (now > resumeTime) resumeTime = 0;
                return;
            }

            if (payTime != null && timeNow.isAfter(payTime.minusSeconds(10)) && !payPending) {
                client.player.networkHandler.sendChatCommand("an" + payServer);
                payPending = true;
            }
            if (payPending && payTime != null && timeNow.getHour() == payTime.getHour() && timeNow.getMinute() == payTime.getMinute() && timeNow.getSecond() >= 30) {
                client.player.networkHandler.sendChatCommand("money");
                waitingForMoney = true;
                payPending = false;
                payTime = null;
                resumeTime = now + 30000L;
            }

            if (joinTime != null && timeNow.getHour() == joinTime.getHour() && timeNow.getMinute() == joinTime.getMinute() && !joinPending) {
                client.player.networkHandler.sendChatCommand("an" + joinServer);
                joinPending = true;
                resumeTime = now + joinDuration;
                joinTime = null;
                joinPending = false;
            }

            if (saveInterval > 0 && now > nextSaveTime) {
                client.player.networkHandler.sendChatCommand("an" + saveServer);
                moneyRequestTime = now + 2000L;
                nextSaveTime = now + saveInterval + getRandOffset();
            }

            if (moneyRequestTime > 0 && now > moneyRequestTime) {
                client.player.networkHandler.sendChatCommand("money");
                waitingForMoney = true;
                moneyRequestTime = 0;
            }

            if (now < nextActionTime) return;
            switch (state) {
                case 0:
                    client.player.networkHandler.sendChatCommand("an" + SERVERS.get(currentServerIndex));
                    state = 4; nextActionTime = now + 1500L + getRandOffset(); break;
                case 4:
                    client.player.networkHandler.sendChatMessage(".");
                    state = 1; nextActionTime = now + (d1 * 1000L) + getRandOffset(); break;
                case 1:
                    queue.clear();
                    if (!storage.isEmpty()) { queue.addAll(storage); messageConfirmed = true; state = 2; }
                    else state = 3;
                    nextActionTime = now; break;
                case 2:
                    if (!queue.isEmpty()) {
                        if (messageConfirmed) {
                            messageConfirmed = false;
                            client.player.networkHandler.sendChatMessage(queue.remove(0));
                            nextActionTime = now + (d2 * 1000L) + getRandOffset();
                        } else { nextActionTime = now + 1000L; messageConfirmed = true; }
                    } else { state = 3; nextActionTime = now + (d3 * 1000L) + getRandOffset(); }
                    break;
                case 3:
                    currentServerIndex++;
                    if (currentServerIndex >= SERVERS.size()) currentServerIndex = 0;
                    state = 0; nextActionTime = now; break;
            }
        });
    }

    private void handleSilentCommand(String input) {
        try {
            String[] p = input.trim().split(" ");
            String cmd = p[0].toLowerCase();
            if (cmd.equals("on")) { active = true; state = 0; nextActionTime = System.currentTimeMillis(); }
            else if (cmd.equals("off")) active = false;
            else if (cmd.equals("zad")) d1 = Integer.parseInt(p[1]);
            else if (cmd.equals("mej")) d2 = Integer.parseInt(p[1]);
            else if (cmd.equals("jdat")) d3 = Integer.parseInt(p[1]);
            else if (cmd.equals("rand")) randRange = Double.parseDouble(p[1]);
            else if (cmd.equals("help1")) {
                MinecraftClient.getInstance().player.sendMessage(Text.of("§6[ClearFire Help]\n§f%on/off §7- Старт/Стоп\n§f%def §7- Сброс настроек\n§f%zad/mej/jdat [сек] §7- Тайминги\n§f%save [an] [time] §7- Авто-инвест\n§f%save off §7- Выкл инвест\n§f%pay [ник] [an] [HH:mm] §7- Перевод\n§f%pay off §7- Отмена перевода\n§f%join [an] [HH:mm] [dur] §7- Заход\n§f%join off §7- Отмена захода"), false);
            }
            else if (cmd.equals("pay")) {
                if (p[1].equals("off")) { payTime = null; payPending = false; payNick = ""; } else {
                    payNick = p[1]; payServer = Integer.parseInt(p[2]);
                    payTime = LocalTime.parse(p[3]); payPending = false;
                }
            }
            else if (cmd.equals("join")) {
                if (p[1].equals("off")) { joinTime = null; joinPending = false; } else {
                    joinServer = Integer.parseInt(p[1]); joinTime = LocalTime.parse(p[2]);
                    String dStr = p[3].toLowerCase();
                    joinDuration = Long.parseLong(dStr.replaceAll("[^0-9]", "")) * (dStr.endsWith("m") ? 60000L : 1000L);
                    joinPending = false;
                }
            }
            else if (cmd.equals("save")) {
                if (p[1].equals("off")) { saveInterval = 0; } else {
                    saveServer = Integer.parseInt(p[1]); String t = p[2].toLowerCase();
                    long m = t.endsWith("s") ? 1000L : t.endsWith("m") ? 60000L : t.endsWith("h") ? 3600000L : 86400000L;
                    saveInterval = Long.parseLong(t.replaceAll("[^0-9]", "")) * m;
                    saveInterval = Math.max(600000L, Math.min(604800000L, saveInterval));
                    nextSaveTime = System.currentTimeMillis() + saveInterval;
                }
            } else if (cmd.equals("def")) {
                storage.clear(); d1=60; d2=3; d3=40; randRange=0.8; saveInterval=0; payTime=null; joinTime=null; storage.add(".");
            } else if (cmd.equals("message")) {
                if (p[1].equals("clear")) storage.clear();
                else {
                    int idx = Integer.parseInt(p[1]) - 1;
                    String text = input.substring(input.indexOf(p[2]));
                    while (storage.size() <= idx) storage.add("");
                    storage.set(idx, text);
                }
            }
        } catch (Exception ignored) {}
    }
}