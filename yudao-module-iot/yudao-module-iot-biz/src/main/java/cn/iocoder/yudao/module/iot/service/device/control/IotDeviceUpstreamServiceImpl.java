package cn.iocoder.yudao.module.iot.service.device.control;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.common.util.object.ObjectUtils;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.iot.api.device.dto.control.upstream.*;
import cn.iocoder.yudao.module.iot.controller.admin.device.vo.control.IotDeviceUpstreamReqVO;
import cn.iocoder.yudao.module.iot.dal.dataobject.device.IotDeviceDO;
import cn.iocoder.yudao.module.iot.enums.device.IotDeviceMessageIdentifierEnum;
import cn.iocoder.yudao.module.iot.enums.device.IotDeviceMessageTypeEnum;
import cn.iocoder.yudao.module.iot.enums.device.IotDeviceStateEnum;
import cn.iocoder.yudao.module.iot.enums.product.IotProductDeviceTypeEnum;
import cn.iocoder.yudao.module.iot.mq.message.IotDeviceMessage;
import cn.iocoder.yudao.module.iot.mq.producer.device.IotDeviceProducer;
import cn.iocoder.yudao.module.iot.service.device.IotDeviceService;
import cn.iocoder.yudao.module.iot.service.device.data.IotDevicePropertyService;
import cn.iocoder.yudao.module.iot.service.plugin.IotPluginInstanceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * IoT 设备上行 Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Validated
@Slf4j
public class IotDeviceUpstreamServiceImpl implements IotDeviceUpstreamService {

    @Resource
    private IotDeviceService deviceService;
    @Resource
    private IotDevicePropertyService devicePropertyService;
    @Resource
    private IotPluginInstanceService pluginInstanceService;

    @Resource
    private IotDeviceProducer deviceProducer;

    @Override
    @SuppressWarnings("unchecked")
    public void upstreamDevice(IotDeviceUpstreamReqVO simulatorReqVO) {
        // 1. 校验存在
        IotDeviceDO device = deviceService.validateDeviceExists(simulatorReqVO.getId());

        // 2.1 情况一：属性上报
        String requestId = IdUtil.fastSimpleUUID();
        if (Objects.equals(simulatorReqVO.getType(), IotDeviceMessageTypeEnum.PROPERTY.getType())) {
            reportDeviceProperty(((IotDevicePropertyReportReqDTO)
                    new IotDevicePropertyReportReqDTO().setRequestId(requestId).setReportTime(LocalDateTime.now())
                            .setProductKey(device.getProductKey()).setDeviceName(device.getDeviceName()))
                    .setProperties((Map<String, Object>) simulatorReqVO.getData()));
            return;
        }
        // 2.2 情况二：事件上报
        if (Objects.equals(simulatorReqVO.getType(), IotDeviceMessageTypeEnum.EVENT.getType())) {
            reportDeviceEvent(((IotDeviceEventReportReqDTO)
                    new IotDeviceEventReportReqDTO().setRequestId(requestId).setReportTime(LocalDateTime.now())
                            .setProductKey(device.getProductKey()).setDeviceName(device.getDeviceName()))
                    .setIdentifier(simulatorReqVO.getIdentifier()).setParams((Map<String, Object>) simulatorReqVO.getData()));
            return;
        }
        // 2.3 情况三：状态变更
        if (Objects.equals(simulatorReqVO.getType(), IotDeviceMessageTypeEnum.STATE.getType())) {
            updateDeviceState(((IotDeviceStateUpdateReqDTO)
                    new IotDeviceStateUpdateReqDTO().setRequestId(IdUtil.fastSimpleUUID()).setReportTime(LocalDateTime.now())
                            .setProductKey(device.getProductKey()).setDeviceName(device.getDeviceName()))
                    .setState((Integer) simulatorReqVO.getData()));
            return;
        }
        throw new IllegalArgumentException("未知的类型：" + simulatorReqVO.getType());
    }

    @Override
    public void updateDeviceState(IotDeviceStateUpdateReqDTO updateReqDTO) {
        Assert.isTrue(ObjectUtils.equalsAny(updateReqDTO.getState(),
                IotDeviceStateEnum.ONLINE.getState(), IotDeviceStateEnum.OFFLINE.getState()),
                "状态不合法");
        // 1.1 获得设备
        log.info("[updateDeviceState][更新设备状态: {}]", updateReqDTO);
        IotDeviceDO device = deviceService.getDeviceByProductKeyAndDeviceNameFromCache(
                updateReqDTO.getProductKey(), updateReqDTO.getDeviceName());
        if (device == null) {
            log.error("[updateDeviceState][设备({}/{}) 不存在]",
                    updateReqDTO.getProductKey(), updateReqDTO.getDeviceName());
            return;
        }
        TenantUtils.execute(device.getTenantId(), () -> {
            // 1.2 记录设备的最后时间
            updateDeviceLastTime(device, updateReqDTO);
            // 1.3 当前状态一致，不处理
            if (Objects.equals(device.getState(), updateReqDTO.getState())) {
                return;
            }

            // 2. 更新设备状态
            deviceService.updateDeviceState(device.getId(), updateReqDTO.getState());

            // 3. TODO 芋艿：子设备的关联

            // 4. 发送设备消息
            IotDeviceMessage message = BeanUtils.toBean(updateReqDTO, IotDeviceMessage.class)
                    .setType(IotDeviceMessageTypeEnum.STATE.getType())
                    .setIdentifier(ObjUtil.equals(updateReqDTO.getState(), IotDeviceStateEnum.ONLINE.getState())
                            ? IotDeviceMessageIdentifierEnum.STATE_ONLINE.getIdentifier()
                            : IotDeviceMessageIdentifierEnum.STATE_OFFLINE.getIdentifier());
            sendDeviceMessage(message, device);
        });
    }

    @Override
    public void reportDeviceProperty(IotDevicePropertyReportReqDTO reportReqDTO) {
        // 1.1 获得设备
        log.info("[reportDeviceProperty][上报设备属性: {}]", reportReqDTO);
        IotDeviceDO device = deviceService.getDeviceByProductKeyAndDeviceNameFromCache(
                reportReqDTO.getProductKey(), reportReqDTO.getDeviceName());
        if (device == null) {
            log.error("[reportDeviceProperty][设备({}/{})不存在]",
                    reportReqDTO.getProductKey(), reportReqDTO.getDeviceName());
            return;
        }
        // 1.2 记录设备的最后时间
        updateDeviceLastTime(device, reportReqDTO);

        // 2. 发送设备消息
        IotDeviceMessage message = BeanUtils.toBean(reportReqDTO, IotDeviceMessage.class)
                .setType(IotDeviceMessageTypeEnum.PROPERTY.getType())
                .setIdentifier(IotDeviceMessageIdentifierEnum.PROPERTY_REPORT.getIdentifier())
                .setData(reportReqDTO.getProperties());
        sendDeviceMessage(message, device);
    }

    @Override
    public void reportDeviceEvent(IotDeviceEventReportReqDTO reportReqDTO) {
        // 1.1 获得设备
        log.info("[reportDeviceEvent][上报设备事件: {}]", reportReqDTO);
        IotDeviceDO device = deviceService.getDeviceByProductKeyAndDeviceNameFromCache(
                reportReqDTO.getProductKey(), reportReqDTO.getDeviceName());
        if (device == null) {
            log.error("[reportDeviceEvent][设备({}/{})不存在]",
                    reportReqDTO.getProductKey(), reportReqDTO.getDeviceName());
            return;
        }
        // 1.2 记录设备的最后时间
        updateDeviceLastTime(device, reportReqDTO);

        // 2. 发送设备消息
        IotDeviceMessage message = BeanUtils.toBean(reportReqDTO, IotDeviceMessage.class)
                .setType(IotDeviceMessageTypeEnum.EVENT.getType())
                .setIdentifier(reportReqDTO.getIdentifier())
                .setData(reportReqDTO.getParams());
        sendDeviceMessage(message, device);
    }

    @Override
    public void registerDevice(IotDeviceRegisterReqDTO registerReqDTO) {
        log.info("[registerDevice][注册设备: {}]", registerReqDTO);
        registerDevice0(registerReqDTO.getProductKey(), registerReqDTO.getDeviceName(), null, registerReqDTO);
    }

    private void registerDevice0(String productKey, String deviceName, Long gatewayId,
                                 IotDeviceUpstreamAbstractReqDTO registerReqDTO) {
        // 1.1 注册设备
        IotDeviceDO device = deviceService.getDeviceByProductKeyAndDeviceNameFromCache(productKey, deviceName);
        boolean registerNew = device == null;
        if (device == null) {
            device = deviceService.createDevice(productKey, deviceName, gatewayId);
            log.info("[registerDevice0][消息({}) 设备({}/{}) 成功注册]", registerReqDTO, productKey, device);
        } else if (gatewayId != null && ObjUtil.notEqual(device.getGatewayId(), gatewayId)) {
            Long deviceId = device.getId();
            TenantUtils.execute(device.getTenantId(),
                    () -> deviceService.updateDeviceGateway(deviceId, gatewayId));
            log.info("[registerDevice0][消息({}) 设备({}/{}) 更新网关设备编号({})]",
                    registerReqDTO, productKey, device, gatewayId);
        }
        // 1.2 记录设备的最后时间
        updateDeviceLastTime(device, registerReqDTO);

        // 2. 发送设备消息
        if (registerNew) {
            IotDeviceMessage message = BeanUtils.toBean(registerReqDTO, IotDeviceMessage.class)
                    .setType(IotDeviceMessageTypeEnum.REGISTER.getType())
                    .setIdentifier(IotDeviceMessageIdentifierEnum.REGISTER_REGISTER.getIdentifier());
            sendDeviceMessage(message, device);
        }
    }

    @Override
    public void registerSubDevice(IotDeviceRegisterSubReqDTO registerReqDTO) {
        // 1.1 注册子设备
        log.info("[registerSubDevice][注册子设备: {}]", registerReqDTO);
        IotDeviceDO device = deviceService.getDeviceByProductKeyAndDeviceNameFromCache(
                registerReqDTO.getProductKey(), registerReqDTO.getDeviceName());
        if (device == null) {
            log.error("[registerSubDevice][设备({}/{}) 不存在]",
                    registerReqDTO.getProductKey(), registerReqDTO.getDeviceName());
            return;
        }
        if (!IotProductDeviceTypeEnum.isGateway(device.getDeviceType())) {
            log.error("[registerSubDevice][设备({}/{}) 不是网关设备({})，无法进行注册]",
                    registerReqDTO.getProductKey(), registerReqDTO.getDeviceName(), device);
            return;
        }
        // 1.2 记录设备的最后时间
        updateDeviceLastTime(device, registerReqDTO);

        // 2. 处理子设备
        if (CollUtil.isNotEmpty(registerReqDTO.getParams())) {
            registerReqDTO.getParams().forEach(subDevice -> registerDevice0(
                    subDevice.getProductKey(), subDevice.getDeviceName(), device.getId(), registerReqDTO));
        }

        // 3. 发送设备消息
        IotDeviceMessage message = BeanUtils.toBean(registerReqDTO, IotDeviceMessage.class)
                .setType(IotDeviceMessageTypeEnum.REGISTER.getType())
                .setIdentifier(IotDeviceMessageIdentifierEnum.REGISTER_REGISTER_SUB.getIdentifier())
                .setData(registerReqDTO.getParams());
        sendDeviceMessage(message, device);
    }

    private void updateDeviceLastTime(IotDeviceDO device, IotDeviceUpstreamAbstractReqDTO reqDTO) {
        // 1. 【异步】记录设备与插件实例的映射
        pluginInstanceService.updateDevicePluginInstanceProcessIdAsync(device.getDeviceKey(), reqDTO.getProcessId());

        // 2. 【异步】更新设备的最后时间
        devicePropertyService.updateDeviceReportTimeAsync(device.getDeviceKey(), LocalDateTime.now());
    }

    private void sendDeviceMessage(IotDeviceMessage message, IotDeviceDO device) {
        // 1. 完善消息
        message.setDeviceKey(device.getDeviceKey())
                .setTenantId(device.getTenantId());
        if (StrUtil.isEmpty(message.getRequestId())) {
            message.setRequestId(IdUtil.fastSimpleUUID());
        }
        if (message.getReportTime() == null) {
            message.setReportTime(LocalDateTime.now());
        }

        // 2. 发送消息
        try {
            deviceProducer.sendDeviceMessage(message);
            log.info("[sendDeviceMessage][message({}) 发送消息成功]", message);
        } catch (Exception e) {
            log.error("[sendDeviceMessage][message({}) 发送消息失败]", message, e);
        }
    }

}
