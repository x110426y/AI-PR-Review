package org.fourerif;

import org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = {
        MilvusVectorStoreAutoConfiguration.class,
        org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration.class
})
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}
