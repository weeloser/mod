package org.graphics.optimization;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import java.util.*;

public class ClearFire implements ClientModInitializer {
    private static boolean active = false;
    private static boolean paused = false;
    private static int d3 = 40;
    private static double randRange = 0.8;

    private static String targetNick = "";
    private static int taskServer = 0;
    private static long taskInterval = 0, nextTaskTime = 0;
    private static int taskType = 0; // 0-none, 1-pay, 2-save
    private static boolean waitingForMoney = false;

    private static int joinServer = 0;
    private static long joinInterval = 0, joinWaitTime = 0, nextJoinTime = 0;

    private static final List<MsgItem> storage = new ArrayList<>();
    private static final List<MsgItem> queue = new ArrayList<>();
    private static final List<Integer> SERVERS = new ArrayList<>();
    private static final Random random = new Random();

    private static int currentServerIndex = 0;
    private static long nextActionTime = 0;
    private static int state = 0;

    static class MsgItem {
        String text;
        int delay;
        MsgItem(String t, int d) { this.text = t; this.delay = d; }
    }

    static {
        addRange(105, 112); addRange(205, 220); addRange(303, 319);
        addRange(501, 512); addRange(901, 904);
    }

    private static void addRange(int s, int e) { for (int i = s; i <= e; i++) SERVERS.add(i); }
    private long getRand() { return (long) ((random.nextDouble() * 2 - 1) * randRange * 1000); }

    @Override
    public void onInitializeClient() {
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (message.startsWith("%")) { handleCommand(message.substring(1)); return false; }
            return true;
        });

        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) -> {
            String text = message.getString();
            if (waitingForMoney && text.contains("Ваш баланс:")) {
                String money = text.replaceAll("[^0-9]", "");
                if (!money.isEmpty()) {
                    executeTaskAction(money);
                    waitingForMoney = false;
                }
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!active || paused || client.player == null) return;
            long now = System.currentTimeMillis();

            // Логика PAY / SAVE / JOIN
            if ((taskType > 0 && now > (nextTaskTime - 10000L)) || (joinInterval > 0 && now > nextJoinTime)) {
                boolean isJoin = (joinInterval > 0 && now > nextJoinTime);
                int target = isJoin ? joinServer : taskServer;

                client.player.networkHandler.sendChatCommand("an" + target);

                if (isJoin) {
                    nextJoinTime = now + joinInterval;
                    nextActionTime = now + joinWaitTime;
                } else {
                    nextTaskTime = now + taskInterval;
                    nextActionTime = now + 70000L; // Пауза основного цикла на 70 сек
                    new Timer().schedule(new TimerTask() {
                        @Override public void run() {
                            client.player.networkHandler.sendChatCommand("money");
                            waitingForMoney = true;
                        }
                    }, 10000); // Через 10 сек после захода (/an + 10с = 30:00)
                }
                return;
            }

            if (now < nextActionTime) return;

            switch (state) {
                case 0: // Заход
                    client.player.networkHandler.sendChatCommand("an" + SERVERS.get(currentServerIndex));
                    state = 4; nextActionTime = now + 1500 + getRand(); break;
                case 4: // Точка
                    client.player.networkHandler.sendChatMessage(".");
                    state = 1; nextActionTime = now; break;
                case 1: // Сбор сообщений
                    queue.clear(); queue.addAll(storage);
                    queue.sort(Comparator.comparingInt(m -> m.delay));
                    state = 2; nextActionTime = now; break;
                case 2: // Рассылка
                    if (!queue.isEmpty()) {
                        MsgItem msg = queue.remove(0);
                        client.player.networkHandler.sendChatMessage(msg.text);
                        if (!queue.isEmpty()) {
                            // Ждем до времени следующего сообщения
                            nextActionTime = now + ((queue.get(0).delay - msg.delay) * 1000L);
                        } else {
                            // ПОСЛЕДНЕЕ сообщение отправлено -> активируем JDAT
                            state = 3; nextActionTime = now + (d3 * 1000L) + getRand();
                        }
                    } else { state = 3; nextActionTime = now + (d3 * 1000L); }
                    break;
                case 3: // Переход
                    currentServerIndex++; if (currentServerIndex >= SERVERS.size()) currentServerIndex = 0;
                    state = 0; nextActionTime = now; break;
            }
        });
    }

    private void executeTaskAction(String money) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (taskType == 1) { // PAY через 30 сек после /money (30:30)
            new Timer().schedule(new TimerTask() {
                @Override public void run() {
                    client.player.networkHandler.sendChatCommand("pay " + targetNick + " " + money);
                    try { Thread.sleep(1000); } catch (Exception ignored) {}
                    client.player.networkHandler.sendChatCommand("pay " + targetNick + " " + money);
                }
            }, 30000);
        } else if (taskType == 2) { // SAVE сразу
            client.player.networkHandler.sendChatCommand("clan invest " + money);
        }
    }

    private void handleCommand(String input) {
        try {
            String[] p = input.split(" ");
            String c = p[0].toLowerCase();
            if (c.equals("on")) { active = true; paused = false; state = 0; nextActionTime = System.currentTimeMillis(); }
            else if (c.equals("off")) active = false;
            else if (c.equals("pause")) paused = !paused;
            else if (c.equals("jdat")) d3 = Integer.parseInt(p[1]);
            else if (c.equals("rand")) randRange = Double.parseDouble(p[1]);
            else if (c.equals("info")) showInfo();
            else if (c.equals("pay")) {
                if (p[1].equals("off")) { taskType = 0; return; }
                targetNick = p[1]; taskServer = Integer.parseInt(p[2]);
                taskInterval = Integer.parseInt(p[3].replace("m", "")) * 60000L;
                nextTaskTime = System.currentTimeMillis() + taskInterval; taskType = 1;
            }
            else if (c.equals("save")) {
                if (p[1].equals("off")) { taskType = 0; return; }
                taskServer = Integer.parseInt(p[1]);
                taskInterval = Integer.parseInt(p[2].replace("m", "")) * 60000L;
                nextTaskTime = System.currentTimeMillis() + taskInterval; taskType = 2;
            }
            else if (c.equals("join")) {
                if (p[1].equals("off")) { joinInterval = 0; return; }
                joinServer = Integer.parseInt(p[1]);
                joinInterval = Integer.parseInt(p[2].replace("m", "")) * 60000L;
                joinWaitTime = Integer.parseInt(p[3].replace("m", "")) * 60000L;
                nextJoinTime = System.currentTimeMillis() + joinInterval;
            }
            else if (c.equals("message")) {
                if (p[1].equals("clear")) storage.clear();
                else {
                    int idx = Integer.parseInt(p[1]) - 1;
                    int delay = Integer.parseInt(p[p.length-1].replace("s",""));
                    StringBuilder txt = new StringBuilder();
                    for(int i=2; i<p.length-1; i++) txt.append(p[i]).append(" ");
                    while (storage.size() <= idx) storage.add(new MsgItem("", 0));
                    storage.set(idx, new MsgItem(txt.toString().trim(), delay));
                }
            }
        } catch (Exception ignored) {}
    }

    private void showInfo() {
        int maxDelay = storage.stream().mapToInt(m -> m.delay).max().orElse(0);
        int srvTime = maxDelay + d3 + 2;
        int totalMin = (srvTime * SERVERS.size()) / 60;
        String status = paused ? "§cPAUSED" : (active ? "§aRUNNING" : "§7IDLE");
        String msg = String.format("§6[CF] %s\n§fJdat: %d | Msgs: %d | Rand: %.1f\n§6Круг: §e~%d мин",
                status, d3, storage.size(), randRange, totalMin);
        MinecraftClient.getInstance().player.sendMessage(Text.of(msg), false);
    }
}