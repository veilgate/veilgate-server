package com.veilgate.privacyguard.llm

import org.springframework.boot.context.properties.ConfigurationProperties

/** application.yml 의 guard.llm (값은 prompt-main/.env 에서 온다) */
@ConfigurationProperties(prefix = "guard.llm")
data class GuardLlmProperties(
    val endpoint: String,
    val apiKey: String,
    val localModel: String,
    val externalModel: String,
)
