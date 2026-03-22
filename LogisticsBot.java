import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;

public class LogisticsBot {
    // Переменные окружения
    private static final String BOT_TOKEN = System.getenv("BOT_TOKEN");
    private static final String LOGIST_GROUP_ID_STR = System.getenv("LOGIST_GROUP_ID");
    private static final long LOGIST_GROUP_ID = Long.parseLong(LOGIST_GROUP_ID_STR);
    private static final String STORAGE_FILE = System.getenv("CARRIER_FILE");

    private static final Set<Long> carrierGroups = new HashSet<>();

    public static void main(String[] args) throws Exception {
        // Проверка, что переменные окружения заданы
        if (BOT_TOKEN == null || LOGIST_GROUP_ID_STR == null) {
            System.err.println("❌ Ошибка: не заданы переменные окружения BOT_TOKEN и LOGIST_GROUP_ID");
            System.exit(1);
        }

        // Загружаем сохранённые группы перевозчиков
        loadGroups();

        // Запускаем HTTP-сервер для приёма webhook
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", new WebhookHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("✅ Бот запущен на порту 8080. Ожидание webhook...");
    }

    // Обработчик входящих запросов от MAX
    static class WebhookHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1); // Method not allowed
                return;
            }

            // Читаем тело запроса
            InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
            BufferedReader br = new BufferedReader(isr);
            StringBuilder requestBody = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                requestBody.append(line);
            }
            String json = requestBody.toString();
            System.out.println("Получено обновление: " + json);

            // Обрабатываем JSON вручную (без библиотек) — это немного громоздко, но работает
            try {
                processUpdate(json);
            } catch (Exception e) {
                System.err.println("Ошибка обработки обновления: " + e.getMessage());
            }

            // Ответ 200 ОК
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        }
    }

    // Простая обработка JSON вручную (ищем нужные поля)
    private static void processUpdate(String json) {
        // Проверяем, есть ли событие "message_created"
        if (!json.contains("\"type\":\"message_created\"")) return;

        // Извлекаем chat_id
        long chatId = extractLong(json, "\"chat_id\":");
        if (chatId == 0) return;

        // Проверяем, добавили ли бота в группу (поле "new_chat_members")
        if (json.contains("\"new_chat_members\"")) {
            // Ищем ID бота в новых участниках
            long botId = getBotId();
            if (json.contains("\"user_id\":" + botId)) {
                // Бота добавили в чат
                if (chatId == LOGIST_GROUP_ID) {
                    System.out.println("Бот добавлен в группу логистов: " + chatId);
                } else {
                    // Это группа перевозчиков
                    if (carrierGroups.add(chatId)) {
                        System.out.println("Добавлена группа перевозчиков: " + chatId);
                        saveGroups();
                    }
                }
                return;
            }
        }

        // Проверяем, не удалили ли бота (поле "left_chat_member")
        if (json.contains("\"left_chat_member\"")) {
            long botId = getBotId();
            if (json.contains("\"user_id\":" + botId)) {
                if (carrierGroups.remove(chatId)) {
                    System.out.println("Удалена группа перевозчиков: " + chatId);
                    saveGroups();
                }
                return;
            }
        }

        // Если это сообщение из группы логистов, то рассылаем текст
        if (chatId == LOGIST_GROUP_ID) {
            String text = extractText(json);
            if (text != null && !text.isEmpty()) {
                System.out.println("📦 Получено задание от логиста: " + text);
                broadcastToCarriers(text);
            }
        }
    }

    // Вспомогательный метод: извлечь число из JSON по ключу
    private static long extractLong(String json, String key) {
        int pos = json.indexOf(key);
        if (pos == -1) return 0;
        pos += key.length();
        int end = pos;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == pos) return 0;
        return Long.parseLong(json.substring(pos, end));
    }

    // Извлечь текст сообщения (поле "text")
    private static String extractText(String json) {
        int pos = json.indexOf("\"text\":\"");
        if (pos == -1) return null;
        pos += 8; // длина "\"text\":\""
        int end = pos;
        while (end < json.length() && json.charAt(end) != '"') {
            end++;
        }
        if (end == pos) return null;
        return json.substring(pos, end);
    }

    // Получить ID бота (один раз, кэшируем)
    private static Long botId = null;
    private static long getBotId() {
        if (botId == null) {
            try {
                String url = "https://api.max.ru/bot" + BOT_TOKEN + "/getMe";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    String json = sb.toString();
                    // Ищем "id":цифра
                    int idx = json.indexOf("\"id\":");
                    if (idx != -1) {
                        idx += 5;
                        int end = idx;
                        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
                        botId = Long.parseLong(json.substring(idx, end));
                    }
                }
            } catch (Exception e) {
                System.err.println("Не удалось получить ID бота: " + e.getMessage());
                botId = 0L;
            }
        }
        return botId;
    }

    // Отправить сообщение во все группы перевозчиков
    private static void broadcastToCarriers(String text) {
        if (carrierGroups.isEmpty()) {
            System.out.println("Нет групп перевозчиков для рассылки.");
            return;
        }
        for (long groupId : carrierGroups) {
            try {
                sendMessage(groupId, text);
                System.out.println("✅ Отправлено в группу " + groupId);
            } catch (Exception e) {
                System.err.println("❌ Ошибка отправки в группу " + groupId + ": " + e.getMessage());
                carrierGroups.remove(groupId);
                saveGroups();
            }
        }
    }

    // Отправить сообщение в конкретный чат через API MAX
    private static void sendMessage(long chatId, String text) throws Exception {
        String url = "https://api.max.ru/bot" + BOT_TOKEN + "/sendMessage";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setDoOutput(true);

        String params = "chat_id=" + chatId + "&text=" + java.net.URLEncoder.encode(text, "UTF-8");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(params.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new IOException("Код ответа: " + responseCode);
        }
    }

    // Загрузить сохранённые группы из файла
    private static void loadGroups() {
        if (STORAGE_FILE == null) {
            System.out.println("CARRIER_FILE не задан, группы не будут сохраняться.");
            return;
        }
        File file = new File(STORAGE_FILE);
        if (!file.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                carrierGroups.add(Long.parseLong(line.trim()));
            }
            System.out.println("Загружено групп перевозчиков: " + carrierGroups.size());
        } catch (Exception e) {
            System.err.println("Ошибка загрузки групп: " + e.getMessage());
        }
    }

    // Сохранить группы в файл
    private static void saveGroups() {
        if (STORAGE_FILE == null) return;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(STORAGE_FILE))) {
            for (long id : carrierGroups) {
                writer.write(String.valueOf(id));
                writer.newLine();
            }
            System.out.println("Сохранено групп перевозчиков: " + carrierGroups.size());
        } catch (IOException e) {
            System.err.println("Ошибка сохранения групп: " + e.getMessage());
        }
    }
}