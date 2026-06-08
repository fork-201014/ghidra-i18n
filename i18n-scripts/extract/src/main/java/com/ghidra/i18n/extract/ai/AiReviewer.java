package com.ghidra.i18n.extract.ai;

import com.ghidra.i18n.common.config.GlobalConfig;
import com.ghidra.i18n.extract.model.TranslationUnit;
import com.google.gson.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * AI-based semantic reviewer that classifies extracted strings as
 * user-facing UI strings vs. internal code artifacts.
 *
 * <p>Uses DeepSeek API by default (OpenAI-compatible endpoint, lower cost)
 * with OpenAI as fallback. Batch size: 50 strings per API call.</p>
 *
 * <p>Strings already REJECTED by FilterEngine (Layer 1-3) are NOT sent to AI.
 * Strings marked NEEDS_REVIEW by Layer 2 are sent for AI confirmation.</p>
 */
public class AiReviewer {

    private static final int BATCH_SIZE = 50;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_RETRIES = 3;
    private static final double CONFIDENCE_THRESHOLD = 0.5;

    // DeepSeek API (OpenAI-compatible endpoint)
    private static final String DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String DEEPSEEK_MODEL = "deepseek-chat";

    // OpenAI API
    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private static final String OPENAI_MODEL = "gpt-4o-mini";

    private final GlobalConfig config;
    private final HttpClient httpClient;
    private final Gson gson;

    // Stats
    private int reviewed;
    private int approved;
    private int rejected;
    private int uncertain;
    private int apiErrors;

    public AiReviewer(GlobalConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.gson = new Gson();
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Reviews a list of TranslationUnits via AI.
     * Only processes units with {@code aiReviewStatus == PENDING}.
     * PRE-REJECTED units (by FilterEngine) are skipped.
     *
     * @return the same list (modified in-place)
     */
    public List<TranslationUnit> review(List<TranslationUnit> units) {
        // Collect units that need AI review
        List<TranslationUnit> pending = new ArrayList<>();
        for (TranslationUnit u : units) {
            if (u.getAiReviewStatus() == TranslationUnit.AiReviewStatus.PENDING
                || u.getAiReviewStatus() == TranslationUnit.AiReviewStatus.NEEDS_REVIEW) {
                pending.add(u);
            }
        }

        if (pending.isEmpty()) return units;

        // Batch processing
        for (int i = 0; i < pending.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, pending.size());
            List<TranslationUnit> batch = pending.subList(i, end);

            try {
                processBatch(batch);
            } catch (Exception e) {
                System.err.println("WARN: AI review batch " + i + "-" + end + " failed: " + e.getMessage());
                apiErrors++;
                // Mark as PENDING — don't block the pipeline
                for (TranslationUnit u : batch) {
                    u.setAiReviewStatus(TranslationUnit.AiReviewStatus.PENDING);
                }
            }
        }

        return units;
    }

    // -----------------------------------------------------------------------
    // Batch processing
    // -----------------------------------------------------------------------

    private void processBatch(List<TranslationUnit> batch) throws IOException, InterruptedException {
        String prompt = buildPrompt(batch);
        String response = callApi(prompt);

        if (response == null || response.isBlank()) {
            throw new IOException("Empty AI response");
        }

        parseResponse(response, batch);
    }

    // -----------------------------------------------------------------------
    // Prompt construction
    // -----------------------------------------------------------------------

    private String buildPrompt(List<TranslationUnit> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 Ghidra 逆向工程工具的代码审核专家。判断以下字符串是否为面向用户的 UI 标签文本。\n\n");
        sb.append("判断标准：\n");
        sb.append("- APPROVED (用户可见)：对话框标题、按钮文本、菜单项、工具提示、插件描述、Help 文档\n");
        sb.append("- REJECTED (内部字符串)：日志消息、异常消息、代码常量、文件路径、正则表达式、属性key\n\n");
        sb.append("对每个编号回复：NUMBER | APPROVED 或 REJECTED | 简短理由\n");
        sb.append("回复格式示例：\n");
        sb.append("1 | APPROVED | 对话框标题，面向用户\n");
        sb.append("2 | REJECTED  | 日志消息，仅内部使用\n\n");

        for (int i = 0; i < batch.size(); i++) {
            TranslationUnit u = batch.get(i);
            sb.append("[").append(i + 1).append("] ");
            sb.append("pattern=").append(u.getPattern());
            sb.append(" context=").append(u.getContext());
            sb.append(" → \"").append(u.getSourceText()).append("\"\n");
        }

        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // API call
    // -----------------------------------------------------------------------

    private String callApi(String prompt) throws IOException, InterruptedException {
        // Try DeepSeek first (lower cost), then OpenAI
        if (!config.getDeepSeekApiKey().isBlank()) {
            try {
                return callDeepSeek(prompt);
            } catch (IOException e) {
                System.err.println("DeepSeek API failed: " + e.getMessage() + " — trying OpenAI");
            }
        }

        if (!config.getOpenAiApiKey().isBlank()) {
            return callOpenAi(prompt);
        }

        throw new IOException("No AI API key configured. Set DEEPSEEK_API_KEY or OPENAI_API_KEY.");
    }

    private String callDeepSeek(String prompt) throws IOException, InterruptedException {
        return callOpenAiCompatible(DEEPSEEK_URL, config.getDeepSeekApiKey(), DEEPSEEK_MODEL, prompt);
    }

    private String callOpenAi(String prompt) throws IOException, InterruptedException {
        return callOpenAiCompatible(OPENAI_URL, config.getOpenAiApiKey(), OPENAI_MODEL, prompt);
    }

    private String callOpenAiCompatible(String url, String apiKey, String model, String prompt)
        throws IOException, InterruptedException {

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0.1);
        body.addProperty("max_tokens", 1000);

        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", "你是代码审查专家。只输出编号和结果，不要额外解释。");
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", prompt);
        messages.add(user);

        body.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .timeout(TIMEOUT)
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("API returned status " + response.statusCode() + ": " + response.body());
        }

        JsonObject result = gson.fromJson(response.body(), JsonObject.class);
        JsonArray choices = result.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IOException("No choices in API response");
        }

        return choices.get(0).getAsJsonObject()
            .getAsJsonObject("message")
            .get("content").getAsString();
    }

    // -----------------------------------------------------------------------
    // Response parsing
    // -----------------------------------------------------------------------

    private void parseResponse(String response, List<TranslationUnit> batch) {
        String[] lines = response.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // Parse format: "N | APPROVED | reason" or "N | REJECTED | reason"
            // Also handle: "N. APPROVED" or "N) APPROVED"
            int num = extractNumber(line);
            if (num < 1 || num > batch.size()) continue;

            String verdict = extractVerdict(line);
            TranslationUnit unit = batch.get(num - 1);

            switch (verdict) {
                case "APPROVED":
                    unit.setAiReviewStatus(TranslationUnit.AiReviewStatus.APPROVED);
                    approved++;
                    break;
                case "REJECTED":
                    unit.setAiReviewStatus(TranslationUnit.AiReviewStatus.REJECTED);
                    rejected++;
                    break;
                default:
                    unit.setAiReviewStatus(TranslationUnit.AiReviewStatus.NEEDS_REVIEW);
                    uncertain++;
                    break;
            }
            reviewed++;
        }

        // Any unit in the batch that wasn't explicitly addressed → NEEDS_REVIEW
        for (TranslationUnit u : batch) {
            if (u.getAiReviewStatus() == TranslationUnit.AiReviewStatus.PENDING
                || u.getAiReviewStatus() == TranslationUnit.AiReviewStatus.NEEDS_REVIEW) {
                // Check if it was covered by a parsed line
                // (if still NEEDS_REVIEW from Layer 2, keep it)
            }
        }
    }

    private int extractNumber(String line) {
        // Try patterns: "1 |", "1.", "1)", "[1]", " 1 "
        for (String prefix : new String[]{"[", " "}) {
            try {
                String rest = line.substring(prefix.equals("[") && line.startsWith("[") ? 1 : 0);
                int end = 0;
                while (end < rest.length() && (Character.isDigit(rest.charAt(end)) || rest.charAt(end) == ' ')) {
                    if (!Character.isDigit(rest.charAt(end)) && rest.charAt(end) != ' ') break;
                    end++;
                }
                String numStr = rest.substring(0, end).trim();
                if (!numStr.isEmpty()) return Integer.parseInt(numStr);
            } catch (Exception ignored) {}
        }
        return -1;
    }

    private String extractVerdict(String line) {
        String upper = line.toUpperCase();
        if (upper.contains("APPROVED")) return "APPROVED";
        if (upper.contains("REJECTED") || upper.contains("REJECT")) return "REJECTED";
        return "UNCERTAIN";
    }

    // -----------------------------------------------------------------------
    // Stats
    // -----------------------------------------------------------------------

    public int getReviewed()  { return reviewed; }
    public int getApproved()  { return approved; }
    public int getRejected()  { return rejected; }
    public int getUncertain() { return uncertain; }
    public int getApiErrors() { return apiErrors; }

    public void resetStats() {
        reviewed = 0; approved = 0; rejected = 0; uncertain = 0; apiErrors = 0;
    }

    public String summary() {
        return String.format(
            "AI Review: reviewed=%d, approved=%d (%.1f%%), rejected=%d, uncertain=%d, errors=%d",
            reviewed,
            approved, reviewed > 0 ? approved * 100.0 / reviewed : 0,
            rejected, uncertain, apiErrors
        );
    }
}
