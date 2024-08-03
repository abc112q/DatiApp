package com.example.aidati.config;

import com.zhipu.oapi.ClientV4;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author Ariel
 */
@Configuration
@ConfigurationProperties(prefix = "ai")
@Data
public class AIConfig {

    private String AIkey;

    /**
     *  new对象可以使用bean的机制在每次项目启动时提前加载好
     *  ClientV4 client = new ClientV4.Builder(AIkey).build();
     *  之后再使用就可以直接使用@Resource方法引入
     */
    @Bean
    public ClientV4 getClientV4() {
        return new ClientV4.Builder(AIkey).build();
    }
}
