package com.example.data.parser

import com.example.data.model.ProtocolType
import com.example.data.model.RoutingMode
import com.example.data.model.RoutingSettings
import com.example.data.model.ServerConfig
import org.json.JSONArray
import org.json.JSONObject

object XrayConfigGenerator {

    /**
     * Generates the Xray configuration used by the local SOCKS5 engine.
     * Android TUN packets are bridged into this SOCKS5 inbound by hev-socks5-tunnel.
     */
    fun generateConfig(
        server: ServerConfig,
        settings: RoutingSettings,
        localSocksPort: Int = 10808,
        localHttpPort: Int = 10809
    ): String {
        val root = JSONObject()

        root.put("log", JSONObject().apply {
            put("access", "")
            put("error", "")
            put("loglevel", "warning")
        })

        // Enable outbound traffic counters for the UI.
        root.put("stats", JSONObject())
        root.put("policy", JSONObject().apply {
            put("levels", JSONObject())
            put("system", JSONObject().apply {
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            })
        })

        val inbounds = JSONArray()
        inbounds.put(JSONObject().apply {
            put("tag", "socks-in")
            put("port", localSocksPort)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray(listOf("http", "tls", "quic")))
            })
        })
        inbounds.put(JSONObject().apply {
            put("tag", "http-in")
            put("port", localHttpPort)
            put("listen", "127.0.0.1")
            put("protocol", "http")
            put("settings", JSONObject().apply { put("timeout", 0) })
        })
        root.put("inbounds", inbounds)

        val outbounds = JSONArray()
        outbounds.put(JSONObject().apply {
            put("tag", "proxy")
            when (server.protocol) {
                ProtocolType.VMESS -> {
                    put("protocol", "vmess")
                    put("settings", JSONObject().apply {
                        put("vnext", JSONArray().put(JSONObject().apply {
                            put("address", server.address)
                            put("port", server.port)
                            put("users", JSONArray().put(JSONObject().apply {
                                put("id", server.uuidOrPassword)
                                put("alterId", server.alterId)
                                put("security", if (server.security.isNotBlank()) server.security else "auto")
                            }))
                        }))
                    })
                }
                ProtocolType.VLESS -> {
                    put("protocol", "vless")
                    put("settings", JSONObject().apply {
                        put("vnext", JSONArray().put(JSONObject().apply {
                            put("address", server.address)
                            put("port", server.port)
                            put("users", JSONArray().put(JSONObject().apply {
                                put("id", server.uuidOrPassword)
                                put("encryption", "none")
                                if (server.flow.isNotBlank()) put("flow", server.flow)
                            }))
                        }))
                    })
                }
                ProtocolType.TROJAN -> {
                    put("protocol", "trojan")
                    put("settings", JSONObject().apply {
                        put("servers", JSONArray().put(JSONObject().apply {
                            put("address", server.address)
                            put("port", server.port)
                            put("password", server.uuidOrPassword)
                        }))
                    })
                }
                ProtocolType.SHADOWSOCKS -> {
                    put("protocol", "shadowsocks")
                    put("settings", JSONObject().apply {
                        put("servers", JSONArray().put(JSONObject().apply {
                            put("address", server.address)
                            put("port", server.port)
                            put("method", if (server.security.isNotBlank()) server.security else "aes-256-gcm")
                            put("password", server.uuidOrPassword)
                            put("ota", false)
                        }))
                    })
                }
            }

            put("streamSettings", JSONObject().apply {
                val net = if (server.networkType.isNotBlank()) server.networkType else "tcp"
                put("network", net)
                when {
                    server.tls.equals("tls", ignoreCase = true) -> {
                        put("security", "tls")
                        put("tlsSettings", JSONObject().apply {
                            put("allowInsecure", false)
                            if (server.sni.isNotBlank()) put("serverName", server.sni)
                            else if (server.host.isNotBlank()) put("serverName", server.host)
                            if (server.alpn.isNotBlank()) put("alpn", JSONArray(server.alpn.split(",").map { it.trim() }))
                        })
                    }
                    server.tls.equals("reality", ignoreCase = true) -> {
                        put("security", "reality")
                        put("realitySettings", JSONObject().apply {
                            put("serverName", if (server.sni.isNotBlank()) server.sni else server.host)
                            put("publicKey", server.publicKey)
                            put("shortId", server.shortId)
                            put("spiderX", "")
                        })
                    }
                    else -> put("security", "none")
                }
                when (net) {
                    "ws" -> put("wsSettings", JSONObject().apply {
                        put("path", if (server.path.isNotBlank()) server.path else "/")
                        put("headers", JSONObject().apply {
                            if (server.host.isNotBlank()) put("Host", server.host)
                        })
                    })
                    "grpc" -> put("grpcSettings", JSONObject().apply {
                        put("serviceName", server.path)
                        put("multiMode", false)
                    })
                    "h2", "http" -> put("httpSettings", JSONObject().apply {
                        put("path", if (server.path.isNotBlank()) server.path else "/")
                        if (server.host.isNotBlank()) put("host", JSONArray(server.host.split(",")))
                    })
                }
            })
        })
        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject())
        })
        outbounds.put(JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
            put("settings", JSONObject())
        })
        root.put("outbounds", outbounds)

        val routingRules = JSONArray()
        if (settings.mode == RoutingMode.BYPASS_LAN) {
            routingRules.put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "direct")
                put("ip", JSONArray(listOf(
                    "10.0.0.0/8",
                    "172.16.0.0/12",
                    "192.168.0.0/16",
                    "127.0.0.0/8",
                    "169.254.0.0/16",
                    "::1/128",
                    "fc00::/7",
                    "fe80::/10"
                )))
            })
        }
        routingRules.put(JSONObject().apply {
            put("type", "field")
            put("outboundTag", "proxy")
            put("port", "0-65535")
        })
        root.put("routing", JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("rules", routingRules)
        })

        // DNS is resolved by Xray and requests are sent through its routing engine.
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray(listOf(settings.dnsServer.ifBlank { "1.1.1.1" }, "8.8.8.8")))
        })

        return root.toString(2)
    }
}
