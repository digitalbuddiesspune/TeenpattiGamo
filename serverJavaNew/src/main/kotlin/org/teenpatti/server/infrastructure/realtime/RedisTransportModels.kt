package org.teenpatti.server.infrastructure.realtime

internal class RedisCommand {
    var requestId: String = ""
    var requesterNodeId: String = ""
    var aggregateType: String = ""
    var aggregateId: String = ""
    var variantId: String? = null
    var commandType: String = ""
    var payload: MutableMap<String, Any?> = linkedMapOf()
}

internal class RedisCommandResult {
    var requestId: String = ""
    var responderNodeId: String = ""
    var status: String = ""
    var data: MutableMap<String, Any?>? = null
    var code: String? = null
    var message: String? = null
}

internal class RedisAggregateEvent {
    var aggregateType: String = ""
    var aggregateId: String = ""
    var eventType: String = ""
}

internal class PublicSocketRequest {
    var type: String = ""
    var requestId: String? = null
    var payload: MutableMap<String, Any?> = linkedMapOf()
}

internal class PrivateSocketRequest {
    var type: String = ""
    var requestId: String? = null
    var payload: MutableMap<String, Any?> = linkedMapOf()
}
