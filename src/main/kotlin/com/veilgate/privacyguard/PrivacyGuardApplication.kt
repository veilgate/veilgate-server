package com.veilgate.privacyguard

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class PrivacyGuardApplication

fun main(args: Array<String>) {
    runApplication<PrivacyGuardApplication>(*args)
}
