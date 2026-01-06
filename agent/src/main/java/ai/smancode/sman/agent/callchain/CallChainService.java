package ai.smancode.sman.agent.callchain;

import ai.smancode.sman.agent.models.CallChainModels.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 调用链分析服务
 *
 * ⚠️ 所有本地处理功能已废弃，必须通过 WebSocket 转发给 IDE Plugin 执行
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
@Service
public class CallChainService {

    private static final Logger log = LoggerFactory.getLogger(CallChainService.class);

    /**
     * 分析调用链
     *
     * ⚠️ 此方法已废弃，必须通过 WebSocket 转发给 IDE Plugin 执行
     *
     * @param request 调用链分析请求
     * @return 调用链分析结果
     * @throws UnsupportedOperationException 此方法必须通过 IDE Plugin 执行
     */
    public CallChainResult analyzeCallChain(CallChainRequest request) {
        log.error("❌ CallChainService.analyzeCallChain() 已废弃，必须通过 IDE Plugin 执行");
        throw new UnsupportedOperationException(
            "call_chain 必须通过 IDE Plugin 执行，请使用 WebSocket 工具转发");
    }
}
