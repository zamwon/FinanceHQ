package com.example.finance_hq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FinanceHqApplication {

	public static void main(String[] args) {
		SpringApplication.run(FinanceHqApplication.class, args);
	}

}
