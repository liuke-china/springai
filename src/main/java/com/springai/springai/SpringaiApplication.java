package com.springai.springai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
// ⚠️ 扫描根必须是公共父包：mapper 实际在 com.springai.springai.smalldemo.mapper（兄弟包，
// 不是 .mapper 子包）。原来写 "com.springai.springai.mapper" 扫不到它们 → UserMapper bean 缺失 → 应用起不来。
@MapperScan("com.springai.springai")
public class SpringaiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringaiApplication.class, args);
    }

}
