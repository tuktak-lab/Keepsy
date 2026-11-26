package com.tuktaklab.keepsy.perf;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 측정 컨텍스트 (Keepsy #350). DataSource를 {@link SqlCapture}로 감싸 실행된 SQL을 모은다 */
@TestConfiguration(proxyBeanMethods = false)
class PerfConfig {

    @Bean
    static SqlCapture sqlCapture() {
        return new SqlCapture();
    }

    @Bean
    static BeanPostProcessor captureDataSource(SqlCapture capture) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof DataSource ds && beanName.equals("dataSource")
                        ? capture.wrap(ds)
                        : bean;
            }
        };
    }
}
