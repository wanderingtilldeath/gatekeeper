package com.gatekeeper.svc_java;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class SvcJavaApplication {

	public static void main(String[] args) {
		SpringApplication.run(SvcJavaApplication.class, args);


	}


}
