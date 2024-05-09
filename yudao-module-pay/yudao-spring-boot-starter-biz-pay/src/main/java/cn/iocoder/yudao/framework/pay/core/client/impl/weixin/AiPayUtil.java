package cn.iocoder.yudao.framework.pay.core.client.impl.weixin;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.pay.core.client.dto.order.PayOrderRespDTO;
import cn.iocoder.yudao.framework.pay.core.client.dto.refund.PayRefundRespDTO;
import cn.iocoder.yudao.framework.pay.core.client.dto.refund.PayRefundUnifiedReqDTO;
import cn.iocoder.yudao.framework.pay.core.enums.order.PayOrderStatusRespEnum;
import com.alibaba.fastjson.JSONObject;
import com.alphapay.api.AlphaPayClient;
import com.alphapay.api.DefaultAlphaPayClient;
import com.alphapay.api.model.beans.*;
import com.alphapay.api.request.beans.CreateOrderRequest;
import com.alphapay.api.request.beans.CreateRefundRequest;
import com.alphapay.api.request.beans.SearchOrderRequest;
import com.alphapay.api.response.beans.CreateOrderResponse;
import com.alphapay.api.response.beans.CreateRefundResponse;
import com.alphapay.api.response.beans.SearchOrderResponse;
import com.github.binarywang.wxpay.bean.request.WxPayUnifiedOrderRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * @author MengSiFan
 * @create 2024/5/9 9:47
 */
@Slf4j
public class AiPayUtil {
    private static final String GATE_WAY_URL = "https://openapi.alphapay.ca";
    private static final String privateKey2 = "MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCClxteJAokHgL3" +
            "nkHz+MlJ5hMhk5faNrE1mRLtTSIfo9gOekAzkIf7ObWaWpzmhwWk1UZh+dwckon3" +
            "RtbVswuXeN/nEimghpH7o1ZWujRz5CsD4EsduQe7Keba1Lck7td5Ad8A63RkcO3u" +
            "BjpB8gBJ0dkn82Mk2LIsGUFuX1VXOJbDj9RD/9MOwpKdfO9KwA2jRN57O7b1D3ex" +
            "JAaenz0kEEPRh69cw15bOuy5xR/L1Y6d0+goR6MVLjQ8sivyhrqTV2rmpB6wO1Hb" +
            "lun/LXhhEzFmRRuM7T0VhFu3vayTGhTuD/tUu0ajLskMPb3PJmS91uGRw5ZDgrIh" +
            "1gRtZ1GTAgMBAAECggEAGs8GkUb4xf1bQpY8l+dE+2S+HLB+BhAgRQ6NsiWZkcFD" +
            "A4XZVDyhjdOFEpDzkOe7IuGdt1Nh+oOiyx7Bz9EX2hq4bGlwHkJCCdS4TsmfJwN6" +
            "SStsgEeR7LxnZhkxF+XoWjEmJLwxgsUkMy8YGp2hrYXk/Kycd938hc0Rf6UWkfb+" +
            "s7qG47uqplUJcHVYpQjpq5iRM7nxW6pbOK5fda2xdzyzi8GXTrcEr5yxvo46LJ1H" +
            "WAiPxj3nGOpVuzyNAsMEhjk0mHraQ62LlmnKp2L/garSx4a5jG7b99X+Ip+QDBYK" +
            "Mhq/vctBcXgPveN49EG1ny1wVWmRdmXnVSQ8ZVVrQQKBgQD/0y/g5JqwFiKmsjm6" +
            "hDqT0hPq8NtNIB//AMzzvZpRUcuJ9wtqYkfxJ/Wmgv5clvpPGm12xMXXJPAY787R" +
            "cOBMUFmF61VWF5RvnnHpZAELkw6ArYPv8OzWbjsQ/vkYKOkaEz6Tf0BFKsDhKJCB" +
            "CKjHIH1fuyXu+ISsEvoIpYQisQKBgQCCrfuCm+ST2BfWzsgwgYj2qvqXvq5oIHFz" +
            "1gXcRKo2gb1Y6somJciAI9TyRxCTlTk/CBoRqeDCB0bsWQ9+SrKCBug5qU71rp0U" +
            "Uh//LPidRmHEDBsTRRMnnsM4+eMC71ig43oIaw7dwq9qHdcyWnAvMs/U0keEW8lv" +
            "mrjKeAzhgwKBgC7qmSZCZogSlypBF0s4gtGnPlXg9CcR1CxdBjlRNWLigFR+BQ7u" +
            "lUkJzghKj8GFQsSNETQt5CaPtKSuHhzU+Z2lQrXHse/HBUbvJO7rkzF/N7Krn726" +
            "ToUI1DZKvH4MyqsoilpchPnqXFMusEmpv+I8+CE9XjiURSiVlltNl40RAoGAfOSh" +
            "CtvJIs+VQRRR2aIDb5RLebmg4B2ZsJas9S6e6wcmGxQSbVERBf6453Cp1BL7KlWo" +
            "7JbKG0ZvLzWTDCWB46mMSoeY0k+3CpPOxseJOG7qwz66pbkPrH8cn5ibsNNlhibC" +
            "G8eN5r3JfobUg+hRbZqHbSQne8VpiIN79u2zidcCgYAQq1lidHGmBOUd5CLiTrN4" +
            "DXWFRefLZuGKsNqpP8bEheXxQSk8aLaJYDmJcGrpsu9G4dCpdboFQOj7fu+HOM8Y" +
            "iOzOoC3p5A5Wy+uu42e6GDAnqFaaarRKdGMgtxw77ZfCj71Mq62YowokhBL2ebD1" +
            "SbRgnOc1aeSAov28RFMjdg==";
    private static final String apub2 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAkEO8REXYwfsfGW4lWG2c11s+ERO4KY/31o2hZS+e4Lj9DFE1KmhCVawi/Sp2wUsnPYr3PaRM4f2Di1oQV9OQ9vAwLqKkG/JhuJ4Jw5iimZiDSrpjNh9RAxjHRCpmcfImDiZ4vjro9oW7o4Dvp+smiDuv09OWs2pjasMAh+sDB30ASZDWOIutSjGPkKdOrYEinAkKUtQAQTqoZbHbnwwCCRI5RJfzFJHZdmbT4QZXemfXZqSncPNFl4uoKPDLG9niaz2LiexoPFCx2KHMzwhPHkfq3oKFQfFdK1NHFV+/bfzksC3DODIX3+GlsFkJo1vWBDlTeqIkHCTA2SQzyeyFOQIDAQAB";

    public static PayOrderRespDTO payOrder(WxPayUnifiedOrderRequest reqDTO){
        CreateOrderRequest request = new CreateOrderRequest();
        request.setMerchantCode("C4GH6K");
        request.setPath("/api/v2.0/payments/pay");
        request.setScenarioCode("MINI_APP");
        request.setPaymentRequestId(reqDTO.getOutTradeNo());
        Order order = new Order();
        Amount amount = new Amount();
        amount.setValue(reqDTO.getTotalFee().toString());
        amount.setCurrency("CAD");
        order.setOrderAmount(amount);
        order.setDescription(reqDTO.getBody());
        order.setNotifyUrl(reqDTO.getNotifyUrl());
        // order.setRedirectUrl("https://alphapay.com/successPage");
        request.setOrder(order);
        PaymentMethod paymentMethod = new PaymentMethod();
        paymentMethod.setPaymentMethodType("Wechat");
        request.setPaymentMethod(paymentMethod);
        Customer customer = new Customer();
        customer.setAppId("wxd521a4e3522e50f0");
        customer.setCustomerId(reqDTO.getOpenid());
        request.setCustomer(customer);
        AlphaPayClient defaultAlphaPayClient = new DefaultAlphaPayClient(GATE_WAY_URL, privateKey2, apub2);
        request.setKeyVersion(1);
        try {
            CreateOrderResponse response = defaultAlphaPayClient.execute(request);
            Map<String, ?> sdkParams = response.getPaymentInfo().getSdkParams();
            String sdkJson = JSONObject.toJSONString(sdkParams);
            PayOrderRespDTO res = new PayOrderRespDTO();
            res.setDisplayContent(sdkJson);
            res.setStatus(0);
            res.setOutTradeNo(response.getPaymentRequestId());
            res.setPaymentId(response.getPaymentId());
            res.setRawData(sdkParams);
            res.setDisplayMode("app");
            return res;
        }catch (Exception e){
            log.info("Ai支付异常：{}",e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Ai支付异常");
        }

    }

    public static PayOrderRespDTO inquiryPayment(String paymentId){
        AlphaPayClient defaultAlphaPayClient = new DefaultAlphaPayClient(GATE_WAY_URL, privateKey2, apub2);
        SearchOrderRequest request = new SearchOrderRequest();
        request.setPaymentId(paymentId);
        request.setMerchantCode("C4GH6K");
        request.setKeyVersion(1);
        request.setPath("/api/v2.0/payments/inquiryPayment");
        PayOrderRespDTO res = new PayOrderRespDTO();
        try {
            // 执行请求
            SearchOrderResponse execute = defaultAlphaPayClient.execute(request);
            Integer status = parseStatus(execute.getPaymentStatus());
            // 转换结果
            res.setStatus(status);
            res.setOutTradeNo(execute.getPaymentRequestId());
            res.setRawData(execute);
            return res;
        } catch (Exception e) {
            log.info("inquiryPayment：{}", e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("inquiryPayment异常");
        }
    }

    public static PayRefundRespDTO refund(PayRefundUnifiedReqDTO reqDTO){
        AlphaPayClient defaultAlphaPayClient = new DefaultAlphaPayClient(GATE_WAY_URL, privateKey2, apub2);
        CreateRefundRequest request = new CreateRefundRequest();
        request.setPaymentId(reqDTO.getPaymentId());
        request.setMerchantCode("C4GH6K");
        request.setKeyVersion(1);
        request.setPath("/api/v2.0/payments/refund");
        request.setRefundRequestId(reqDTO.getOutRefundNo());
        Refund refund = new Refund();
        Amount amount = new Amount();
        amount.setValue(reqDTO.getRefundPrice().toString());
        refund.setRefundAmount(amount);
        refund.setDescription(reqDTO.getOutRefundNo());
        refund.setNotifyUrl(reqDTO.getNotifyUrl());
        request.setRefund(refund);
        try {
            log.info("退款请求参数：{}",request);
            // 执行请求
            CreateRefundResponse execute = defaultAlphaPayClient.execute(request);
            log.info("退款状态getResultCode：{}",execute.getResult().getResultCode());
            log.info("退款状态getResultStatus：{}",execute.getResult().getResultStatus());
            log.info("退款详情：{}",JSONObject.toJSONString(execute));
            PayRefundRespDTO res = new PayRefundRespDTO();
            res.setStatus(0);
            res.setOutRefundNo(execute.getRefundRequestId());
            return res;
        } catch (Exception e) {
            log.info("inquiryPayment：{}", e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("inquiryPayment异常");
        }
    }

    private static Integer parseStatus(String tradeState) {
        switch (tradeState) {
            case "OFFLINE_QRCODE":
            case "PAYMENT_IN_PROCESS": // 支付中，等待用户输入密码（条码支付独有）
                return PayOrderStatusRespEnum.WAITING.getStatus();
            case "SUCCESS":
                return PayOrderStatusRespEnum.SUCCESS.getStatus();
            case "PARTIAL_REFUND":
                return PayOrderStatusRespEnum.REFUND.getStatus();
            case "FULL_REFUND":
                return PayOrderStatusRespEnum.REFUND.getStatus();
            case "CLOSED":
            case "REVOKED": // 已撤销（刷卡支付独有）
            case "FAILED": // 支付失败（其它原因，如银行返回失败）
                return PayOrderStatusRespEnum.CLOSED.getStatus();
            default:
                throw new IllegalArgumentException(StrUtil.format("未知的支付状态({})", tradeState));
        }
    }


}
