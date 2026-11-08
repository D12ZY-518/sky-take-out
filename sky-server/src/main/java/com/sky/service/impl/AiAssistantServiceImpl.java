package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sky.constant.StatusConstant;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.ShoppingCart;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.service.AiAssistantService;
import com.sky.vo.AiChatVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class AiAssistantServiceImpl implements AiAssistantService {

    // ---- DeepSeek（OpenAI 兼容接口）配置，从 yml 注入 ----
    @Value("${sky.ai.api-key}")
    private String apiKey;

    @Value("${sky.ai.base-url}")
    private String baseUrl;

    @Value("${sky.ai.model}")
    private String model;

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private SetmealMapper setmealMapper;

    @Autowired
    private ShoppingCartMapper shoppingCartMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    // Redis 会话历史：key = ai:chat:history:{userId}
    private static final String HISTORY_KEY_PREFIX = "ai:chat:history:";
    // 只保留最近 10 轮对话（user+assistant 共 20 条），防上下文无限膨胀
    private static final int MAX_HISTORY_ROUNDS = 10;
    private static final long HISTORY_TTL_MINUTES = 60;

    @Override
    public AiChatVO chat(Long userId, String message) {
        // 1. 读该用户多轮会话历史（Redis），裁剪到最近 10 轮
        JSONArray history = loadHistory(userId);
        JSONArray kept = new JSONArray();
        for (int i = Math.max(0, history.size() - MAX_HISTORY_ROUNDS * 2); i < history.size(); i++) {
            kept.add(history.getJSONObject(i));
        }

        // 2. 组装 LLM 消息：system（菜单快照做 RAG 知识注入）+ 历史 + 本轮
        JSONArray messages = new JSONArray();
        messages.add(buildMessage("system", buildSystemPrompt()));
        for (int i = 0; i < kept.size(); i++) {
            messages.add(kept.getJSONObject(i));
        }
        messages.add(buildMessage("user", message));

        // 3. 调 DeepSeek，要求只输出 JSON（response_format=json_object）
        String content = invokeDeepSeek(messages);

        // 4. 解析意图 JSON；解析失败按 chat 兜底
        JSONObject intent = content == null ? null : parseIntent(content);

        // 5. 按意图分派：加购/看购物车走"后端以库为准"，绝不信模型编造的回复
        String reply;
        String intentType = intent == null ? null : intent.getString("intent");
        if ("add_cart".equals(intentType)) {
            reply = doAddCart(userId, intent);
        } else if ("view_cart".equals(intentType)) {
            reply = buildCartText(userId);
        } else if (intent != null && intent.getString("reply") != null) {
            reply = intent.getString("reply");
        } else {
            reply = content == null
                    ? "AI 服务暂时开小差了（检查 DEEPSEEK_API_KEY 是否配置），稍后再试～"
                    : "我没太听明白，可以说“推荐两个菜”或“把宫保鸡丁加入购物车”试试。";
        }

        // 6. 本轮对话写回历史（user + assistant），设置 TTL
        JSONArray newHistory = new JSONArray();
        for (int i = 0; i < kept.size(); i++) {
            newHistory.add(kept.getJSONObject(i));
        }
        newHistory.add(buildMessage("user", message));
        newHistory.add(buildMessage("assistant", reply));
        saveHistory(userId, newHistory);

        // 7. 返回回复 + 当前购物车总件数（页面角标）
        return AiChatVO.builder().reply(reply).cartCount(countCartItems(userId)).build();
    }

    @Override
    public List<ShoppingCart> cart(Long userId) {
        ShoppingCart query = new ShoppingCart();
        query.setUserId(userId);
        return shoppingCartMapper.list(query);
    }

    // ==================== 私有方法 ====================

    /**
     * 菜单快照：从库里拉"在售"菜品+套餐，拼成 JSON 塞进 system prompt。
     * 这是轻量 RAG（知识注入）：模型只能看到真实存在的商品，从源头掐掉"编造菜品"。
     */
    private String buildSystemPrompt() {
        JSONArray snapshot = new JSONArray();
        Dish dishQuery = new Dish();
        dishQuery.setStatus(StatusConstant.ENABLE);
        List<Dish> dishes = dishMapper.list(dishQuery);
        Setmeal setmealQuery = new Setmeal();
        setmealQuery.setStatus(StatusConstant.ENABLE);
        List<Setmeal> setmeals = setmealMapper.list(setmealQuery);
        log.info("AI 菜单快照：在售菜品 {} 个、套餐 {} 个", dishes.size(), setmeals.size());
        for (Dish dish : dishes) {
            JSONObject item = new JSONObject();
            item.put("kind", "dish");
            item.put("id", dish.getId());
            item.put("name", dish.getName());
            item.put("price", dish.getPrice().toPlainString());
            item.put("description", dish.getDescription() == null ? "" : dish.getDescription());
            snapshot.add(item);
        }
        for (Setmeal setmeal : setmeals) {
            JSONObject item = new JSONObject();
            item.put("kind", "setmeal");
            item.put("id", setmeal.getId());
            item.put("name", setmeal.getName());
            item.put("price", setmeal.getPrice().toPlainString());
            item.put("description", setmeal.getDescription() == null ? "" : setmeal.getDescription());
            snapshot.add(item);
        }
        String prompt = "你是\"禾巷餐饮\"微信小程序的 AI 点餐助手，帮顾客推荐菜品、回答菜单问题、下单点餐。\n"
                + "当前在售菜单 JSON 快照（只有这个范围内的商品真实存在，kind=dish 单菜品 / kind=setmeal 套餐）：\n"
                + snapshot.toJSONString()
                + "\n\n严格规则：\n"
                + "1. 只允许推荐快照中真实存在的商品；严禁编造菜品名、价格、口味、优惠、折扣。\n"
                + "2. 用户明确要点某个商品时：intent=add_cart，kind 与 id 必须取自快照，quantity 默认 1（范围 1~99）。\n"
                + "3. 表述模糊（如\"辣的\"\"招牌菜\"）没有唯一对应时不加购，intent=chat，用追问和用户确认是哪一道。\n"
                + "4. 用户询问已点内容/购物车时：intent=view_cart，不要自己编造购物车内容。\n"
                + "5. 闲聊、推荐、回答其他问题时：intent=chat，回复简洁自然的中文，可引用名称与价格。\n"
                + "6. 一道菜的口味选择如果不确定，引导用户到菜品详情页选择，不要自作主张。\n"
                + "7. 用户说\"再来一份\"\"同样再来\"\"再要一份\"，且上文刚讨论过或刚加购过某道具体菜品时：默认对那道菜 intent=add_cart（quantity=1），id 取自快照；上文没明确是哪道菜则不猜，intent=chat 追问。\n\n"
                + "只输出一个 JSON 对象（不要 markdown 代码块、不要多余文字）：\n"
                + "{\"reply\":\"对用户说的话\",\"intent\":\"chat 或 add_cart 或 view_cart\",\"kind\":\"dish 或 setmeal（add_cart 时必填）\",\"id\":商品id（add_cart 时必填，取自快照）,\"quantity\":数量整数}";
        return prompt;
    }

    /**
     * 调用 DeepSeek Chat Completions（OpenAI 兼容），要求 json_object 输出
     */
    private String invokeDeepSeek(JSONArray messages) {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", 0.3);
        body.put("stream", false);
        JSONObject responseFormat = new JSONObject();
        responseFormat.put("type", "json_object");
        body.put("response_format", responseFormat);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.error("DeepSeek 调用失败 status={} body={}", response.statusCode(), response.body());
                return null;
            }
            JSONObject result = JSON.parseObject(response.body());
            JSONArray choices = result.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            return choices.getJSONObject(0).getJSONObject("message").getString("content");
        } catch (Exception e) {
            log.error("DeepSeek 调用异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 剥掉模型可能带上的 markdown 围栏，再解析成 JSON
     */
    private JSONObject parseIntent(String content) {
        try {
            String text = content.trim();
            if (text.startsWith("```")) {
                text = text.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "");
            }
            return JSON.parseObject(text);
        } catch (Exception e) {
            log.error("AI 返回非 JSON: {}", content);
            return null;
        }
    }

    /**
     * 加购闭环（防幻觉核心）：模型只给 kind+id，这里以数据库为准再校验一次——
     * 商品不存在/已停售/数量越界一律不落库，只回引导话术。
     */
    private String doAddCart(Long userId, JSONObject intent) {
        String kind = intent.getString("kind");
        Long itemId = intent.getLong("id");
        int quantity = intent.getIntValue("quantity");
        if (itemId == null) {
            return "想加哪个菜呢？告诉我名字就行，比如“来一份宫保鸡丁”。";
        }
        if (quantity <= 0) {
            quantity = 1;
        }
        if (quantity > 99) {
            quantity = 99;
        }
        String name;
        if ("dish".equals(kind)) {
            Dish dish = dishMapper.getById(itemId);
            if (dish == null || !StatusConstant.ENABLE.equals(dish.getStatus())) {
                return "这道菜暂时点不了，看看别的吧～";
            }
            name = dish.getName();
            addOrMerge(userId, dish.getId(), null, name, dish.getImage(), dish.getPrice(), quantity);
        } else if ("setmeal".equals(kind)) {
            Setmeal setmeal = setmealMapper.getById(itemId);
            if (setmeal == null || !StatusConstant.ENABLE.equals(setmeal.getStatus())) {
                return "这个套餐暂时点不了，看看别的吧～";
            }
            name = setmeal.getName();
            addOrMerge(userId, null, setmeal.getId(), name, setmeal.getImage(), setmeal.getPrice(), quantity);
        } else {
            return "想加哪个菜呢？告诉我名字就行，比如“来一份宫保鸡丁”。";
        }
        return "已把「" + name + " × " + quantity + "」加入购物车，还要点别的吗？";
    }

    /**
     * 写入购物车：同款已存在则数量累加，否则新插一行（冗余商品快照字段）
     */
    private void addOrMerge(Long userId, Long dishId, Long setmealId, String name,
                            String image, BigDecimal price, int quantity) {
        ShoppingCart query = new ShoppingCart();
        query.setUserId(userId);
        query.setDishId(dishId);
        query.setSetmealId(setmealId);
        List<ShoppingCart> exists = shoppingCartMapper.list(query);
        if (!exists.isEmpty()) {
            ShoppingCart row = exists.get(0);
            row.setNumber(row.getNumber() + quantity);
            shoppingCartMapper.updateNumberById(row);
            return;
        }
        ShoppingCart row = new ShoppingCart();
        row.setUserId(userId);
        row.setDishId(dishId);
        row.setSetmealId(setmealId);
        row.setName(name);
        row.setImage(image);
        row.setAmount(price);
        row.setNumber(quantity);
        row.setCreateTime(LocalDateTime.now());
        shoppingCartMapper.insert(row);
    }

    /**
     * 组装购物车文案（view_cart 意图由后端查库生成，不信模型编的内容）
     */
    private String buildCartText(Long userId) {
        ShoppingCart query = new ShoppingCart();
        query.setUserId(userId);
        List<ShoppingCart> list = shoppingCartMapper.list(query);
        if (list == null || list.isEmpty()) {
            return "你的购物车还是空的，想吃什么告诉我，我帮你加～";
        }
        StringBuilder sb = new StringBuilder("你目前的购物车：\n");
        BigDecimal total = BigDecimal.ZERO;
        for (ShoppingCart item : list) {
            BigDecimal line = item.getAmount().multiply(new BigDecimal(item.getNumber()));
            total = total.add(line);
            sb.append("· ").append(item.getName()).append(" × ").append(item.getNumber())
                    .append("　小计 ¥").append(line.toPlainString()).append("\n");
        }
        sb.append("合计：¥").append(total.toPlainString());
        return sb.toString();
    }

    /**
     * 购物车商品总件数（页面角标）
     */
    private int countCartItems(Long userId) {
        ShoppingCart query = new ShoppingCart();
        query.setUserId(userId);
        List<ShoppingCart> list = shoppingCartMapper.list(query);
        int sum = 0;
        for (ShoppingCart item : list) {
            sum += item.getNumber();
        }
        return sum;
    }

    private JSONObject buildMessage(String role, String content) {
        JSONObject msg = new JSONObject();
        msg.put("role", role);
        msg.put("content", content);
        return msg;
    }

    private JSONArray loadHistory(Long userId) {
        String json = stringRedisTemplate.opsForValue().get(HISTORY_KEY_PREFIX + userId);
        if (json == null) {
            return new JSONArray();
        }
        try {
            return JSON.parseArray(json);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void saveHistory(Long userId, JSONArray history) {
        String key = HISTORY_KEY_PREFIX + userId;
        stringRedisTemplate.opsForValue().set(key, history.toJSONString(), HISTORY_TTL_MINUTES, TimeUnit.MINUTES);
    }
}
