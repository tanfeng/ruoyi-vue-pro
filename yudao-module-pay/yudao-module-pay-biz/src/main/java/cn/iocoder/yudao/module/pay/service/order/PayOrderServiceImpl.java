package cn.iocoder.yudao.module.pay.service.order;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.date.LocalDateTimeUtils;
import cn.iocoder.yudao.framework.common.util.number.MoneyUtils;
import cn.iocoder.yudao.framework.pay.core.client.PayClient;
import cn.iocoder.yudao.framework.pay.core.client.dto.order.PayOrderRespDTO;
import cn.iocoder.yudao.framework.pay.core.client.dto.order.PayOrderUnifiedReqDTO;
import cn.iocoder.yudao.framework.pay.core.enums.order.PayOrderStatusRespEnum;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.pay.api.order.dto.PayOrderCreateReqDTO;
import cn.iocoder.yudao.module.pay.controller.admin.order.vo.PayOrderExportReqVO;
import cn.iocoder.yudao.module.pay.controller.admin.order.vo.PayOrderPageReqVO;
import cn.iocoder.yudao.module.pay.controller.admin.order.vo.PayOrderSubmitReqVO;
import cn.iocoder.yudao.module.pay.controller.admin.order.vo.PayOrderSubmitRespVO;
import cn.iocoder.yudao.module.pay.convert.order.PayOrderConvert;
import cn.iocoder.yudao.module.pay.dal.dataobject.app.PayAppDO;
import cn.iocoder.yudao.module.pay.dal.dataobject.channel.PayChannelDO;
import cn.iocoder.yudao.module.pay.dal.dataobject.order.PayOrderDO;
import cn.iocoder.yudao.module.pay.dal.dataobject.order.PayOrderExtensionDO;
import cn.iocoder.yudao.module.pay.dal.mysql.order.PayOrderExtensionMapper;
import cn.iocoder.yudao.module.pay.dal.mysql.order.PayOrderMapper;
import cn.iocoder.yudao.module.pay.dal.redis.no.PayNoRedisDAO;
import cn.iocoder.yudao.module.pay.enums.notify.PayNotifyTypeEnum;
import cn.iocoder.yudao.module.pay.enums.order.PayOrderStatusEnum;
import cn.iocoder.yudao.module.pay.framework.pay.config.PayProperties;
import cn.iocoder.yudao.module.pay.service.app.PayAppService;
import cn.iocoder.yudao.module.pay.service.channel.PayChannelService;
import cn.iocoder.yudao.module.pay.service.notify.PayNotifyService;
import com.alphapay.api.AlphaPayClient;
import com.alphapay.api.DefaultAlphaPayClient;
import com.alphapay.api.exception.AlphaPayApiException;
import com.alphapay.api.model.beans.Amount;
import com.alphapay.api.model.beans.Customer;
import com.alphapay.api.model.beans.Order;
import com.alphapay.api.model.beans.PaymentMethod;
import com.alphapay.api.request.beans.CreateOrderRequest;
import com.alphapay.api.response.beans.CreateOrderResponse;
import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString;
import static cn.iocoder.yudao.module.pay.enums.ErrorCodeConstants.*;

/**
 * 支付订单 Service 实现类
 *
 * @author aquan
 */
@Service
@Validated
@Slf4j
public class PayOrderServiceImpl implements PayOrderService {

    @Resource
    private PayProperties payProperties;

    @Resource
    private PayOrderMapper orderMapper;
    @Resource
    private PayOrderExtensionMapper orderExtensionMapper;
    @Resource
    private PayNoRedisDAO noRedisDAO;

    @Resource
    private PayAppService appService;
    @Resource
    private PayChannelService channelService;
    @Resource
    private PayNotifyService notifyService;

    @Override
    public PayOrderDO getOrder(Long id) {
        return orderMapper.selectById(id);
    }

    @Override
    public PayOrderDO getOrder(Long appId, String merchantOrderId) {
        return orderMapper.selectByAppIdAndMerchantOrderId(appId, merchantOrderId);
    }

    @Override
    public List<PayOrderDO> getOrderList(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return orderMapper.selectBatchIds(ids);
    }

    @Override
    public Long getOrderCountByAppId(Long appId) {
        return orderMapper.selectCountByAppId(appId);
    }

    @Override
    public PageResult<PayOrderDO> getOrderPage(PayOrderPageReqVO pageReqVO) {
        return orderMapper.selectPage(pageReqVO);
    }

    @Override
    public List<PayOrderDO> getOrderList(PayOrderExportReqVO exportReqVO) {
        return orderMapper.selectList(exportReqVO);
    }

    @Override
    public Long createOrder(PayOrderCreateReqDTO reqDTO) {
        // 校验 App
        PayAppDO app = appService.validPayApp(reqDTO.getAppId());

        // 查询对应的支付交易单是否已经存在。如果是，则直接返回
        PayOrderDO order = orderMapper.selectByAppIdAndMerchantOrderId(
                reqDTO.getAppId(), reqDTO.getMerchantOrderId());
        if (order != null) {
            log.warn("[createOrder][appId({}) merchantOrderId({}) 已经存在对应的支付单({})]", order.getAppId(),
                    order.getMerchantOrderId(), toJsonString(order)); // 理论来说，不会出现这个情况
            return order.getId();
        }

        // 创建支付交易单
        order = PayOrderConvert.INSTANCE.convert(reqDTO).setAppId(app.getId())
                // 商户相关字段
                .setNotifyUrl(app.getOrderNotifyUrl())
                // 订单相关字段
                .setStatus(PayOrderStatusEnum.WAITING.getStatus())
                // 退款相关字段
                .setRefundPrice(0);
        orderMapper.insert(order);
        return order.getId();
    }

    @Override // 注意，这里不能添加事务注解，避免调用支付渠道失败时，将 PayOrderExtensionDO 回滚了
    public PayOrderSubmitRespVO submitOrder(PayOrderSubmitReqVO reqVO, String userIp) {
        // 1.1 获得 PayOrderDO ，并校验其是否存在
        PayOrderDO order = validateOrderCanSubmit(reqVO.getId());
        // 1.32 校验支付渠道是否有效
        PayChannelDO channel = validateChannelCanSubmit(order.getAppId(), reqVO.getChannelCode());
        PayClient client = channelService.getPayClient(channel.getId());

        // 2. 插入 PayOrderExtensionDO
        String no = noRedisDAO.generate(payProperties.getOrderNoPrefix());
        PayOrderExtensionDO orderExtension = PayOrderConvert.INSTANCE.convert(reqVO, userIp)
                .setOrderId(order.getId()).setNo(no)
                .setChannelId(channel.getId()).setChannelCode(channel.getCode())
                .setStatus(PayOrderStatusEnum.WAITING.getStatus());
        orderExtensionMapper.insert(orderExtension);

        // 3. 调用三方接口
        PayOrderUnifiedReqDTO unifiedOrderReqDTO = PayOrderConvert.INSTANCE.convert2(reqVO, userIp)
                // 商户相关的字段
                .setOutTradeNo(orderExtension.getNo()) // 注意，此处使用的是 PayOrderExtensionDO.no 属性！
                .setSubject(order.getSubject()).setBody(order.getBody())
                .setNotifyUrl(genChannelOrderNotifyUrl(channel))
                .setReturnUrl(reqVO.getReturnUrl())
                // 订单相关字段
                .setPrice(order.getPrice()).setExpireTime(order.getExpireTime());
        PayOrderRespDTO unifiedOrderResp = client.unifiedOrder(unifiedOrderReqDTO);
        orderExtension.setPaymentId(unifiedOrderResp.getPaymentId());
        orderExtensionMapper.updateById(orderExtension);
        // 4. 如果调用直接支付成功，则直接更新支付单状态为成功。例如说：付款码支付，免密支付时，就直接验证支付成功
        if (unifiedOrderResp != null) {
            getSelf().notifyOrder(channel, unifiedOrderResp);
            // 如有渠道错误码，则抛出业务异常，提示用户
            if (StrUtil.isNotEmpty(unifiedOrderResp.getChannelErrorCode())) {
                throw exception(PAY_ORDER_SUBMIT_CHANNEL_ERROR, unifiedOrderResp.getChannelErrorCode(),
                        unifiedOrderResp.getChannelErrorMsg());
            }
            // 此处需要读取最新的状态
            order = orderMapper.selectById(order.getId());
        }
        return PayOrderConvert.INSTANCE.convert(order, unifiedOrderResp);
    }

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


    public PayOrderSubmitRespVO submitOrder2(PayOrderSubmitReqVO reqVO, String userIp) throws AlphaPayApiException {
        // 1.1 获得 PayOrderDO ，并校验其是否存在
        PayOrderDO order = validateOrderCanSubmit(reqVO.getId());
        // 1.32 校验支付渠道是否有效
/*        PayChannelDO channel = validateChannelCanSubmit(order.getAppId(), reqVO.getChannelCode());
        PayClient client = channelService.getPayClient(channel.getId());*/

        // 2. 插入 PayOrderExtensionDO
        String no = noRedisDAO.generate(payProperties.getOrderNoPrefix());
        PayOrderExtensionDO orderExtension = PayOrderConvert.INSTANCE.convert(reqVO, userIp)
                .setOrderId(order.getId()).setNo(no)
                .setChannelId(111L).setChannelCode("mypay")
                .setStatus(PayOrderStatusEnum.WAITING.getStatus());
        orderExtensionMapper.insert(orderExtension);

        // 3. 调用三方接口


        CreateOrderRequest request = new CreateOrderRequest();
        request.setMerchantCode("C4GH6K");
        request.setPath("/api/v2.0/payments/pay");

        request.setScenarioCode("MINI_APP");
        String uuid = RandomUtil.randomString(32);
        request.setPaymentRequestId(uuid);
        Order apOrder = new Order();
        Amount amount = new Amount();
        amount.setValue("100");
        amount.setCurrency("CAD");
        apOrder.setOrderAmount(amount);
        apOrder.setDescription("Test OFFLINE_QRCODE");
        apOrder.setNotifyUrl("https://www.pyberclub.com/app-api/trade/order/update-paid");
        apOrder.setRedirectUrl("https://alphapay.com/successPage");
        request.setOrder(apOrder);
        PaymentMethod paymentMethod = new PaymentMethod();
        paymentMethod.setPaymentMethodType("Wechat");
        request.setPaymentMethod(paymentMethod);
        Customer customer = new Customer();
        customer.setAppId("wxd521a4e3522e50f0");
        customer.setCustomerId("oJm-a675HrYLqzSAaVtyvGaFQBow");
        request.setCustomer(customer);
        AlphaPayClient defaultAlphaPayClient = new DefaultAlphaPayClient(GATE_WAY_URL, privateKey2, apub2);
        request.setKeyVersion(1);
        CreateOrderResponse response = defaultAlphaPayClient.execute(request);

        PayOrderSubmitRespVO res = new PayOrderSubmitRespVO();

        // 4. 如果调用直接支付成功，则直接更新支付单状态为成功。例如说：付款码支付，免密支付时，就直接验证支付成功
        if (res != null) {

            // 此处需要读取最新的状态
            order = orderMapper.selectById(order.getId());
        }
        return res;
    }

    private PayOrderDO validateOrderCanSubmit(Long id) {
        PayOrderDO order = orderMapper.selectById(id);
        if (order == null) { // 是否存在
            throw exception(PAY_ORDER_NOT_FOUND);
        }
        if (PayOrderStatusEnum.isSuccess(order.getStatus())) { // 校验状态，发现已支付
            throw exception(PAY_ORDER_STATUS_IS_SUCCESS);
        }
        if (!PayOrderStatusEnum.WAITING.getStatus().equals(order.getStatus())) { // 校验状态，必须是待支付
            throw exception(PAY_ORDER_STATUS_IS_NOT_WAITING);
        }
        if (LocalDateTimeUtils.beforeNow(order.getExpireTime())) { // 校验是否过期
            throw exception(PAY_ORDER_IS_EXPIRED);
        }

        // 【重要】校验是否支付拓展单已支付，只是没有回调、或者数据不正常
        validateOrderActuallyPaid(id);
        return order;
    }

    /**
     * 校验支付订单实际已支付
     *
     * @param id 支付编号
     */
    @VisibleForTesting
    void validateOrderActuallyPaid(Long id) {
        List<PayOrderExtensionDO> orderExtensions = orderExtensionMapper.selectListByOrderId(id);
        orderExtensions.forEach(orderExtension -> {
            // 情况一：校验数据库中的 orderExtension 是不是已支付
            if (PayOrderStatusEnum.isSuccess(orderExtension.getStatus())) {
                log.warn("[validateOrderCanSubmit][order({}) 的 extension({}) 已支付，可能是数据不一致]",
                        id, orderExtension.getId());
                throw exception(PAY_ORDER_EXTENSION_IS_PAID);
            }
            // 情况二：调用三方接口，查询支付单状态，是不是已支付
            PayClient payClient = channelService.getPayClient(orderExtension.getChannelId());
            if (payClient == null) {
                log.error("[validateOrderCanSubmit][渠道编号({}) 找不到对应的支付客户端]", orderExtension.getChannelId());
                return;
            }
            // ai支付得用他们平台的订单id才能查询订单
            if(orderExtension.getPaymentId() == null){
                return;
            }
            PayOrderRespDTO respDTO = payClient.getOrder(orderExtension.getPaymentId());
            if (respDTO != null && PayOrderStatusRespEnum.isSuccess(respDTO.getStatus())) {
                log.warn("[validateOrderCanSubmit][order({}) 的 PayOrderRespDTO({}) 已支付，可能是回调延迟]",
                        id, toJsonString(respDTO));
                throw exception(PAY_ORDER_EXTENSION_IS_PAID);
            }
        });
    }

    private PayChannelDO validateChannelCanSubmit(Long appId, String channelCode) {
        // 校验 App
        appService.validPayApp(appId);
        // 校验支付渠道是否有效
        PayChannelDO channel = channelService.validPayChannel(appId, channelCode);
        PayClient client = channelService.getPayClient(channel.getId());
        if (client == null) {
            log.error("[validatePayChannelCanSubmit][渠道编号({}) 找不到对应的支付客户端]", channel.getId());
            throw exception(CHANNEL_NOT_FOUND);
        }
        return channel;
    }

    /**
     * 根据支付渠道的编码，生成支付渠道的回调地址
     *
     * @param channel 支付渠道
     * @return 支付渠道的回调地址  配置地址 + "/" + channel id
     */
    private String genChannelOrderNotifyUrl(PayChannelDO channel) {
        return payProperties.getOrderNotifyUrl() + "/" + channel.getId();
    }

    @Override
    public void notifyOrder(Long channelId, PayOrderRespDTO notify) {
        // 校验支付渠道是否有效
        PayChannelDO channel = channelService.validPayChannel(channelId);
        // 更新支付订单为已支付
        TenantUtils.execute(channel.getTenantId(), () -> getSelf().notifyOrder(channel, notify));
    }

    /**
     * 通知并更新订单的支付结果
     *
     * @param channel 支付渠道
     * @param notify  通知
     */
    @Transactional(rollbackFor = Exception.class)
    // 注意，如果是方法内调用该方法，需要通过 getSelf().notifyPayOrder(channel, notify) 调用，否则事务不生效
    public void notifyOrder(PayChannelDO channel, PayOrderRespDTO notify) {
        // 情况一：支付成功的回调
        if (PayOrderStatusRespEnum.isSuccess(notify.getStatus())) {
            notifyOrderSuccess(channel, notify);
            return;
        }
        // 情况二：支付失败的回调
        if (PayOrderStatusRespEnum.isClosed(notify.getStatus())) {
            notifyOrderClosed(channel, notify);
        }
        // 情况三：WAITING：无需处理
        // 情况四：REFUND：通过退款回调处理
    }

    private void notifyOrderSuccess(PayChannelDO channel, PayOrderRespDTO notify) {
        // 1. 更新 PayOrderExtensionDO 支付成功
        PayOrderExtensionDO orderExtension = updateOrderSuccess(notify);
        // 2. 更新 PayOrderDO 支付成功
        Boolean paid = updateOrderSuccess(channel, orderExtension, notify);
        if (paid) { // 如果之前已经成功回调，则直接返回，不用重复记录支付通知记录；例如说：支付平台重复回调
            return;
        }

        // 3. 插入支付通知记录
        notifyService.createPayNotifyTask(PayNotifyTypeEnum.ORDER.getType(),
                orderExtension.getOrderId());
    }

    /**
     * 更新 PayOrderExtensionDO 支付成功
     *
     * @param notify 通知
     * @return PayOrderExtensionDO 对象
     */
    private PayOrderExtensionDO updateOrderSuccess(PayOrderRespDTO notify) {
        // 1. 查询 PayOrderExtensionDO
        PayOrderExtensionDO orderExtension = orderExtensionMapper.selectByNo(notify.getOutTradeNo());
        if (orderExtension == null) {
            throw exception(PAY_ORDER_EXTENSION_NOT_FOUND);
        }
        if (PayOrderStatusEnum.isSuccess(orderExtension.getStatus())) { // 如果已经是成功，直接返回，不用重复更新
            log.info("[updateOrderExtensionSuccess][orderExtension({}) 已经是已支付，无需更新]", orderExtension.getId());
            return orderExtension;
        }
        if (ObjectUtil.notEqual(orderExtension.getStatus(), PayOrderStatusEnum.WAITING.getStatus())) { // 校验状态，必须是待支付
            throw exception(PAY_ORDER_EXTENSION_STATUS_IS_NOT_WAITING);
        }

        // 2. 更新 PayOrderExtensionDO
        int updateCounts = orderExtensionMapper.updateByIdAndStatus(orderExtension.getId(), orderExtension.getStatus(),
                PayOrderExtensionDO.builder().status(PayOrderStatusEnum.SUCCESS.getStatus()).channelNotifyData(toJsonString(notify)).build());
        if (updateCounts == 0) { // 校验状态，必须是待支付
            throw exception(PAY_ORDER_EXTENSION_STATUS_IS_NOT_WAITING);
        }
        log.info("[updateOrderExtensionSuccess][orderExtension({}) 更新为已支付]", orderExtension.getId());
        return orderExtension;
    }

    /**
     * 更新 PayOrderDO 支付成功
     *
     * @param channel        支付渠道
     * @param orderExtension 支付拓展单
     * @param notify         通知回调
     * @return 是否之前已经成功回调
     */
    private Boolean updateOrderSuccess(PayChannelDO channel, PayOrderExtensionDO orderExtension,
                                       PayOrderRespDTO notify) {
        // 1. 判断 PayOrderDO 是否处于待支付
        PayOrderDO order = orderMapper.selectById(orderExtension.getOrderId());
        if (order == null) {
            throw exception(PAY_ORDER_NOT_FOUND);
        }
        if (PayOrderStatusEnum.isSuccess(order.getStatus()) // 如果已经是成功，直接返回，不用重复更新
                && Objects.equals(order.getExtensionId(), orderExtension.getId())) {
            log.info("[updateOrderExtensionSuccess][order({}) 已经是已支付，无需更新]", order.getId());
            return true;
        }
        if (!PayOrderStatusEnum.WAITING.getStatus().equals(order.getStatus())) { // 校验状态，必须是待支付
            throw exception(PAY_ORDER_STATUS_IS_NOT_WAITING);
        }

        // 2. 更新 PayOrderDO
        int updateCounts = orderMapper.updateByIdAndStatus(order.getId(), PayOrderStatusEnum.WAITING.getStatus(),
                PayOrderDO.builder().status(PayOrderStatusEnum.SUCCESS.getStatus())
                        .channelId(channel.getId()).channelCode(channel.getCode())
                        .successTime(notify.getSuccessTime()).extensionId(orderExtension.getId()).no(orderExtension.getNo())
                        .channelOrderNo(notify.getChannelOrderNo()).channelUserId(notify.getChannelUserId())
                        .channelFeeRate(channel.getFeeRate())
                        .channelFeePrice(MoneyUtils.calculateRatePrice(order.getPrice(), channel.getFeeRate()))
                        .build());
        if (updateCounts == 0) { // 校验状态，必须是待支付
            throw exception(PAY_ORDER_STATUS_IS_NOT_WAITING);
        }
        log.info("[updateOrderExtensionSuccess][order({}) 更新为已支付]", order.getId());
        return false;
    }

    private void notifyOrderClosed(PayChannelDO channel, PayOrderRespDTO notify) {
        updateOrderExtensionClosed(channel, notify);
    }

    private void updateOrderExtensionClosed(PayChannelDO channel, PayOrderRespDTO notify) {
        // 1. 查询 PayOrderExtensionDO
        PayOrderExtensionDO orderExtension = orderExtensionMapper.selectByNo(notify.getOutTradeNo());
        if (orderExtension == null) {
            throw exception(PAY_ORDER_EXTENSION_NOT_FOUND);
        }
        if (PayOrderStatusEnum.isClosed(orderExtension.getStatus())) { // 如果已经是关闭，直接返回，不用重复更新
            log.info("[updateOrderExtensionClosed][orderExtension({}) 已经是支付关闭，无需更新]", orderExtension.getId());
            return;
        }
        // 一般出现先是支付成功，然后支付关闭，都是全部退款导致关闭的场景。这个情况，我们不更新支付拓展单，只通过退款流程，更新支付单
        if (PayOrderStatusEnum.isSuccess(orderExtension.getStatus())) {
            log.info("[updateOrderExtensionClosed][orderExtension({}) 是已支付，无需更新为支付关闭]", orderExtension.getId());
            return;
        }
        if (ObjectUtil.notEqual(orderExtension.getStatus(), PayOrderStatusEnum.WAITING.getStatus())) { // 校验状态，必须是待支付
            throw exception(PAY_ORDER_EXTENSION_STATUS_IS_NOT_WAITING);
        }

        // 2. 更新 PayOrderExtensionDO
        int updateCounts = orderExtensionMapper.updateByIdAndStatus(orderExtension.getId(), orderExtension.getStatus(),
                PayOrderExtensionDO.builder().status(PayOrderStatusEnum.CLOSED.getStatus()).channelNotifyData(toJsonString(notify))
                        .channelErrorCode(notify.getChannelErrorCode()).channelErrorMsg(notify.getChannelErrorMsg()).build());
        if (updateCounts == 0) { // 校验状态，必须是待支付
            throw exception(PAY_ORDER_EXTENSION_STATUS_IS_NOT_WAITING);
        }
        log.info("[updateOrderExtensionClosed][orderExtension({}) 更新为支付关闭]", orderExtension.getId());
    }

    @Override
    public void updateOrderRefundPrice(Long id, Integer incrRefundPrice) {
        PayOrderDO order = orderMapper.selectById(id);
        if (order == null) {
            throw exception(PAY_ORDER_NOT_FOUND);
        }
        if (!PayOrderStatusEnum.isSuccessOrRefund(order.getStatus())) {
            throw exception(PAY_ORDER_REFUND_FAIL_STATUS_ERROR);
        }
        if (order.getRefundPrice() + incrRefundPrice > order.getPrice()) {
            throw exception(REFUND_PRICE_EXCEED);
        }

        // 更新订单
        PayOrderDO updateObj = new PayOrderDO()
                .setRefundPrice(order.getRefundPrice() + incrRefundPrice)
                .setStatus(PayOrderStatusEnum.REFUND.getStatus());
        int updateCount = orderMapper.updateByIdAndStatus(id, order.getStatus(), updateObj);
        if (updateCount == 0) {
            throw exception(PAY_ORDER_REFUND_FAIL_STATUS_ERROR);
        }
    }

    @Override
    public void updatePayOrderPrice(Long id, Integer payPrice) {
        PayOrderDO order = orderMapper.selectById(id);
        if (order == null) {
            throw exception(PAY_ORDER_NOT_FOUND);
        }
        if (ObjectUtil.notEqual(PayOrderStatusEnum.WAITING.getStatus(), order.getStatus())) {
            throw exception(PAY_ORDER_STATUS_IS_NOT_WAITING);
        }
        if (ObjectUtil.equal(order.getPrice(), payPrice)) {
            return;
        }

        // TODO 芋艿：应该 new 出来更新
        order.setPrice(payPrice);
        orderMapper.updateById(order);
    }

    @Override
    public PayOrderExtensionDO getOrderExtension(Long id) {
        return orderExtensionMapper.selectById(id);
    }

    @Override
    public PayOrderExtensionDO getOrderExtensionByNo(String no) {
        return orderExtensionMapper.selectByNo(no);
    }

    @Override
    public int syncOrder(LocalDateTime minCreateTime) {
        // 1. 查询指定创建时间前的待支付订单
        List<PayOrderExtensionDO> orderExtensions = orderExtensionMapper.selectListByStatusAndCreateTimeGe(
                PayOrderStatusEnum.WAITING.getStatus(), minCreateTime);
        if (CollUtil.isEmpty(orderExtensions)) {
            return 0;
        }
        // 2. 遍历执行
        int count = 0;
        for (PayOrderExtensionDO orderExtension : orderExtensions) {
            count += syncOrder(orderExtension) ? 1 : 0;
        }
        return count;
    }

    /**
     * 同步单个支付拓展单
     *
     * @param orderExtension 支付拓展单
     * @return 是否已支付
     */
    private boolean syncOrder(PayOrderExtensionDO orderExtension) {
        try {
            // 1.1 查询支付订单信息
            PayClient payClient = channelService.getPayClient(orderExtension.getChannelId());
            if (payClient == null) {
                log.error("[syncOrder][渠道编号({}) 找不到对应的支付客户端]", orderExtension.getChannelId());
                return false;
            }
            PayOrderRespDTO respDTO = payClient.getOrder(orderExtension.getNo());
            // 如果查询到订单不存在，PayClient 返回的状态为关闭。但此时不能关闭订单。存在以下一种场景：
            //  拉起渠道支付后，短时间内用户未及时完成支付，但是该订单同步定时任务恰巧自动触发了，主动查询结果为订单不存在。
            //  当用户支付成功之后，该订单状态在渠道的回调中无法从已关闭改为已支付，造成重大影响。
            // 考虑此定时任务是异常场景的兜底操作，因此这里不做变更，优先以回调为准。
            // 让订单自动随着支付渠道那边一起等到过期，确保渠道先过期关闭支付入口，而后通过订单过期定时任务关闭自己的订单。
            if (PayOrderStatusRespEnum.isClosed(respDTO.getStatus())) {
                return false;
            }
            // 1.2 回调支付结果
            notifyOrder(orderExtension.getChannelId(), respDTO);

            // 2. 如果是已支付，则返回 true
            return PayOrderStatusRespEnum.isSuccess(respDTO.getStatus());
        } catch (Throwable e) {
            log.error("[syncOrder][orderExtension({}) 同步支付状态异常]", orderExtension.getId(), e);
            return false;
        }
    }

    @Override
    public int expireOrder() {
        // 1. 查询过期的待支付订单
        List<PayOrderDO> orders = orderMapper.selectListByStatusAndExpireTimeLt(
                PayOrderStatusEnum.WAITING.getStatus(), LocalDateTime.now());
        if (CollUtil.isEmpty(orders)) {
            return 0;
        }

        // 2. 遍历执行
        int count = 0;
        for (PayOrderDO order : orders) {
            count += expireOrder(order) ? 1 : 0;
        }
        return count;
    }

    /**
     * 同步单个支付单
     *
     * @param order 支付单
     * @return 是否已过期
     */
    private boolean expireOrder(PayOrderDO order) {
        try {
            // 1. 需要先处理关联的支付拓展单，避免错误的过期已支付 or 已退款的订单
            List<PayOrderExtensionDO> orderExtensions = orderExtensionMapper.selectListByOrderId(order.getId());
            for (PayOrderExtensionDO orderExtension : orderExtensions) {
                if (PayOrderStatusEnum.isClosed(orderExtension.getStatus())) {
                    continue;
                }
                // 情况一：校验数据库中的 orderExtension 是不是已支付
                if (PayOrderStatusEnum.isSuccess(orderExtension.getStatus())) {
                    log.error("[expireOrder][order({}) 的 extension({}) 已支付，可能是数据不一致]",
                            order.getId(), orderExtension.getId());
                    return false;
                }
                // 情况二：调用三方接口，查询支付单状态，是不是已支付/已退款
                PayClient payClient = channelService.getPayClient(orderExtension.getChannelId());
                if (payClient == null) {
                    log.error("[expireOrder][渠道编号({}) 找不到对应的支付客户端]", orderExtension.getChannelId());
                    return false;
                }
                PayOrderRespDTO respDTO = payClient.getOrder(orderExtension.getNo());
                if (PayOrderStatusRespEnum.isRefund(respDTO.getStatus())) {
                    // 补充说明：按道理，应该是 WAITING => SUCCESS => REFUND 状态，如果直接 WAITING => REFUND 状态，说明中间丢了过程
                    // 此时，需要人工介入，手工补齐数据，保持 WAITING => SUCCESS => REFUND 的过程
                    log.error("[expireOrder][extension({}) 的 PayOrderRespDTO({}) 已退款，可能是回调延迟]",
                            orderExtension.getId(), toJsonString(respDTO));
                    return false;
                }
                if (PayOrderStatusRespEnum.isSuccess(respDTO.getStatus())) {
                    notifyOrder(orderExtension.getChannelId(), respDTO);
                    return false;
                }
                // 兜底逻辑：将支付拓展单更新为已关闭
                PayOrderExtensionDO updateObj = new PayOrderExtensionDO().setStatus(PayOrderStatusEnum.CLOSED.getStatus())
                        .setChannelNotifyData(toJsonString(respDTO));
                if (orderExtensionMapper.updateByIdAndStatus(orderExtension.getId(), PayOrderStatusEnum.WAITING.getStatus(),
                        updateObj) == 0) {
                    log.error("[expireOrder][extension({}) 更新为支付关闭失败]", orderExtension.getId());
                    return false;
                }
                log.info("[expireOrder][extension({}) 更新为支付关闭成功]", orderExtension.getId());
            }

            // 2. 都没有上述情况，可以安心更新为已关闭
            PayOrderDO updateObj = new PayOrderDO().setStatus(PayOrderStatusEnum.CLOSED.getStatus());
            if (orderMapper.updateByIdAndStatus(order.getId(), order.getStatus(), updateObj) == 0) {
                log.error("[expireOrder][order({}) 更新为支付关闭失败]", order.getId());
                return false;
            }
            log.info("[expireOrder][order({}) 更新为支付关闭失败]", order.getId());
            return true;
        } catch (Throwable e) {
            log.error("[expireOrder][order({}) 过期订单异常]", order.getId(), e);
            return false;
        }
    }

    /**
     * 获得自身的代理对象，解决 AOP 生效问题
     *
     * @return 自己
     */
    private PayOrderServiceImpl getSelf() {
        return SpringUtil.getBean(getClass());
    }

}
