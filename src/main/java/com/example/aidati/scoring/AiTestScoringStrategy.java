package com.example.aidati.scoring;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.aidati.manager.AIManager;
import com.example.aidati.model.dto.question.QuestionAnswerDTO;
import com.example.aidati.model.dto.question.QuestionContentDTO;
import com.example.aidati.model.entity.App;
import com.example.aidati.model.entity.Question;
import com.example.aidati.model.entity.ScoringResult;
import com.example.aidati.model.entity.UserAnswer;
import com.example.aidati.model.vo.QuestionVO;
import com.example.aidati.service.QuestionService;
import com.example.aidati.service.ScoringResultService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.util.DigestUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * AI测评类应用评分策略
 * @author Ariel
 */
@ScoringStrategyConfig(appType = 1, scoringStrategy = 1)
public class AiTestScoringStrategy implements ScoringStrategy {

    @Resource
    private QuestionService questionService;

    @Resource
    private AIManager aiManager;

    @Resource
    private RedissonClient redissonClient;

    // 分布式锁的 key
    private static final String AI_ANSWER_LOCK = "AI_ANSWER_LOCK";

    /**
     * AI评分结果本地缓存
     */
    private final Cache<String,String> answerCacheMap =
            // 初始化容量
            Caffeine.newBuilder().initialCapacity(1024)
                    // 缓存五分钟移除
                    .expireAfterAccess(5L, TimeUnit.MINUTES)
                    .build();

    /**
     * AI 评分系统消息
     */
    private static final String AI_TEST_SCORING_SYSTEM_MESSAGE = "你是一位严谨的判题专家，我会给你如下信息：\n" +
            "```\n" +
            "应用名称，\n" +
            "【【【应用描述】】】，\n" +
            "题目和用户回答的列表：格式为 [{\"title\": \"题目\",\"answer\": \"用户回答\"}]\n" +
            "```\n" +
            "\n" +
            "请你根据上述信息，按照以下步骤来对用户进行评价：\n" +
            "1. 要求：需要给出一个明确的评价结果，包括评价名称（尽量简短）和评价描述（尽量详细，大于 200 字）\n" +
            "2. 严格按照下面的 json 格式输出评价名称和评价描述\n" +
            "```\n" +
            "{\"resultName\": \"评价名称\", \"resultDesc\": \"评价描述\"}\n" +
            "```\n" +
            "3. 返回格式必须为 JSON 对象";

    /**
     * AI 评分用户消息封装
     *
     * @param app
     * @param questionContentDTOList
     * @param choices
     * @return
     */
    private String getAiTestScoringUserMessage(App app, List<QuestionContentDTO> questionContentDTOList, List<String> choices) {
        StringBuilder userMessage = new StringBuilder();
        userMessage.append(app.getAppName()).append("\n");
        userMessage.append(app.getAppDesc()).append("\n");
        List<QuestionAnswerDTO> questionAnswerDTOList = new ArrayList<>();
        for (int i = 0; i < questionContentDTOList.size(); i++) {
            QuestionAnswerDTO questionAnswerDTO = new QuestionAnswerDTO();
            questionAnswerDTO.setTitle(questionContentDTOList.get(i).getTitle());
            questionAnswerDTO.setUserAnswer(choices.get(i));
            questionAnswerDTOList.add(questionAnswerDTO);
        }
        userMessage.append(JSONUtil.toJsonStr(questionAnswerDTOList));
        return userMessage.toString();
    }

    /**
     * 使用本地缓存 并且使用分布式锁解决缓存击穿问题
     * @param choices
     * @param app
     * @return
     * @throws Exception
     */
    @Override
    public UserAnswer doScore(List<String> choices, App app) throws Exception {
        Long appId = app.getId();
        // 将choices转换为json字符串来构建缓存key
        String cacheKey = BuildCacheKey(appId, JSONUtil.toJsonStr(choices));
        // 如果有缓存，直接返回
        String answerJson = answerCacheMap.getIfPresent(cacheKey);
        if (StrUtil.isNotBlank(answerJson)) {
            // 构造返回结果
            UserAnswer userAnswer = JSONUtil.toBean(answerJson, UserAnswer.class);
            userAnswer.setAppId(appId);
            userAnswer.setAppType(app.getAppType());
            userAnswer.setScoringStrategy(app.getScoringStrategy());
            userAnswer.setChoices(JSONUtil.toJsonStr(choices));
            return userAnswer;
        }
        // 定义锁   try catch保证即使代码有问题，最终所以会被释放
        RLock lock = redissonClient.getLock(AI_ANSWER_LOCK + cacheKey);
        try{
            // 抢锁
            boolean res = lock.tryLock(3, 15,TimeUnit.SECONDS);
            // 抢锁失败 强制返回
            if(!res){
                return null;
            }
            // 抢锁成功 执行业务逻辑
            // 1. 根据 id 查询到题目和题目结果信息
            Question question = questionService.getOne(
                    Wrappers.lambdaQuery(Question.class).eq(Question::getAppId, appId)
            );
            // 获取题目列表
            QuestionVO questionVO = QuestionVO.objToVo(question);
            List<QuestionContentDTO> questionContent = questionVO.getQuestionContent();
            // 调用AI获取结果
            String userMessage = getAiTestScoringUserMessage(app, questionContent, choices);
            final String result = aiManager.doSyncStableRequest(AI_TEST_SCORING_SYSTEM_MESSAGE, userMessage);
            // 截取需要的的json信息
            int start = result.indexOf("{");
            int end = result.lastIndexOf("}") + 1;
            String json = result.substring(start, end);

            // 如果没有缓存就直接放结果进去缓存起来
            answerCacheMap.put(cacheKey,answerJson);

            // 3. 构造返回值，填充答案对象的属性
            UserAnswer userAnswer= JSONUtil.toBean(json, UserAnswer.class);
            userAnswer.setAppId(appId);
            userAnswer.setAppType(app.getAppType());
            userAnswer.setScoringStrategy(app.getScoringStrategy());
            userAnswer.setChoices(JSONUtil.toJsonStr(choices));
            return userAnswer;
        }catch (Exception e){
            throw e;
        }finally {
            // 释放锁  注意只有本人可以释放自己的锁
            if(lock != null && lock.isLocked()){
                if(lock.isHeldByCurrentThread()){
                    lock.unlock();
                }
            }
        }
    }


    /**
     * 构建缓存key
     * @param appId
     * @param choices
     * @return
     */
    private String BuildCacheKey(Long appId,String choices){
        // 使用MD5压缩大key
        return DigestUtil.md5Hex(appId + ":" + choices);
    }
}
