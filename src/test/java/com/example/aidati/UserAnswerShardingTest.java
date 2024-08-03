package com.example.aidati;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.aidati.model.entity.UserAnswer;
import com.example.aidati.service.UserAnswerService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
public class UserAnswerShardingTest {

    @Resource
    private UserAnswerService userAnswerService;

    @Test
    void test() {

        UserAnswer userAnswer1 = new UserAnswer();
        userAnswer1.setAppId(1L);
        userAnswer1.setUserId(1L);
        userAnswer1.setChoices("1");
        // 配置好后会自动存在appId取模结果为1的表
        userAnswerService.save(userAnswer1);

        UserAnswer userAnswer2 = new UserAnswer();
        userAnswer2.setAppId(2L);
        userAnswer2.setUserId(1L);
        userAnswer2.setChoices("2");
        // 配置好后会自动存在appId取模结果为0的表
        userAnswerService.save(userAnswer2);

        // 根据sql看他们来源于哪个表
        UserAnswer userAnswerOne = userAnswerService.getOne(Wrappers.lambdaQuery(UserAnswer.class).eq(UserAnswer::getAppId, 1L));
        System.out.println(JSONUtil.toJsonStr(userAnswerOne));

        UserAnswer userAnswerTwo = userAnswerService.getOne(Wrappers.lambdaQuery(UserAnswer.class).eq(UserAnswer::getAppId, 2L));
        System.out.println(JSONUtil.toJsonStr(userAnswerTwo));
    }
}
