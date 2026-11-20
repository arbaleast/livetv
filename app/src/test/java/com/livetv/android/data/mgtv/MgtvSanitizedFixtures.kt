package com.livetv.android.data.mgtv

/**
 * **脱敏** MGTV 响应 fixture。
 *
 * 合规要求：单元测试只允许使用脱敏后的样本，严禁把真实 Cookie / Token / did /
 * sign / ftc / sftc / 真实 IP / 带签名的直播地址写进仓库。
 *
 * 因此这里的域名统一使用 RFC 2606 保留的 `.invalid` 顶级域，签名/设备标识均为
 * 明显的人造占位串（`SANITIZED_*`），与任何真实会话无关。
 */
internal object MgtvSanitizedFixtures {

    /** 频道目录（JSONP 形态，保留 `livecallback(...)` 包裹）。 */
    const val CATEGORY_JSONP = """
        livecallback({"errno":"0","data":{"category":[
          {"category_id":"cat_sanitized_1","name":"卫视频道","channels":[
            {"id":"287","name":"金鹰卡通","channel_image":"https://cdn.example.invalid/logo/sanitized.png","channel_number":"287","curr_program":"脱敏示例节目"},
            {"id":"288","name":"脱敏频道二","channel_image":"","channel_number":"288","curr_program":""}
          ]},
          {"category_id":"cat_sanitized_2","name":"其他分类","channels":[
            {"id":"289","name":"脱敏频道三","channel_image":"","channel_number":"","curr_program":""}
          ]}
        ]}});
    """

    /** 频道目录（纯 JSON 形态，等价于去掉 JSONP 包装）。 */
    const val CATEGORY_JSON = """
        {"errno":"0","data":{"category":[
          {"category_id":"cat_sanitized_1","name":"卫视频道","channels":[
            {"id":"287","name":"金鹰卡通","channel_image":"https://cdn.example.invalid/logo/sanitized.png","channel_number":"287","curr_program":"脱敏示例节目"}
          ]}
        ]}}
    """

    /** 目录业务错误码。 */
    const val CATEGORY_ERROR_JSON = """{"errno":"2040114","data":{"category":[]}}"""

    /** 播放源响应：标准非 P2P、非 HDCP 的 HTTPS `.m3u8`。 */
    const val SOURCE_OK = """
        {"code":"0","msg":"","data":{
          "cameraId":"287","cameraName":"金鹰卡通","isp2p":0,"hdcp":0,"servertime":1700000000,
          "sources":[
            {"url":"https://cdn.example.invalid/nn_live/nn_x64/SANITIZED/index.m3u8?es=1700000000","format":".m3u8","videoFormat":"h264","audioFormat":"AAC","definition":"2","name":"360P","urlExpireDuration":28800,"sid":"SANITIZED_SID","isothercdn":0}
          ]
        }}
    """

    /** 播放源响应：同一响应内混合 P2P / HDCP / 非 HTTPS / 非 m3u8 四种不合规源。 */
    const val SOURCE_MIXED = """
        {"code":"0","msg":"","data":{
          "cameraId":"287","cameraName":"金鹰卡通","isp2p":0,"hdcp":0,"servertime":1700000000,
          "sources":[
            {"url":"https://cdn.example.invalid/ok/360.m3u8","format":".m3u8","definition":"2","name":"360P","urlExpireDuration":28800},
            {"url":"https://cdn.example.invalid/p2p/720.m3u8","format":".m3u8","definition":"3","name":"720P","urlExpireDuration":28800,"isp2p":1},
            {"url":"https://cdn.example.invalid/hdcp/1080.m3u8","format":".m3u8","definition":"4","name":"1080P","urlExpireDuration":28800,"hdcp":1},
            {"url":"http://cdn.example.invalid/plain/480.m3u8","format":".m3u8","definition":"5","name":"480P","urlExpireDuration":28800},
            {"url":"https://cdn.example.invalid/flv/360.flv","format":".flv","definition":"6","name":"360P-FLV","urlExpireDuration":28800}
          ]
        }}
    """

    /** 播放源业务错误（等价于签名校验失败）。 */
    const val SOURCE_SIGN_ERROR = """{"code":"2040117","msg":"签名不正确","data":{}}"""

    /** 播放源缺少可用源。 */
    const val SOURCE_EMPTY = """
        {"code":"0","msg":"","data":{
          "cameraId":"287","cameraName":"金鹰卡通","isp2p":1,"hdcp":0,"servertime":1700000000,"sources":[]
        }}
    """
}
