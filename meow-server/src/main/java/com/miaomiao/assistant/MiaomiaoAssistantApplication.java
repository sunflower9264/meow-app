package com.miaomiao.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动程序
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class MiaomiaoAssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(MiaomiaoAssistantApplication.class, args);
        System.out.println("(♥◠‿◠)ﾉﾞ  Miaomiao Assistant Extension Server启动成功   ლ(´ڡ`ლ)ﾞ  \n");
    }
}
