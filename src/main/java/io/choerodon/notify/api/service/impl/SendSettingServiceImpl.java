package io.choerodon.notify.api.service.impl;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import io.choerodon.core.enums.ResourceType;
import io.choerodon.core.exception.CommonException;
import io.choerodon.core.notify.Level;
import io.choerodon.core.notify.NotifyType;
import io.choerodon.core.notify.ServiceNotifyType;
import io.choerodon.notify.api.dto.*;
import io.choerodon.notify.api.service.MessageSettingService;
import io.choerodon.notify.api.service.SendSettingService;
import io.choerodon.notify.api.validator.CommonValidator;
import io.choerodon.notify.api.vo.SendSettingCategoryVO;
import io.choerodon.notify.api.vo.WebHookVO;
import io.choerodon.notify.infra.dto.*;
import io.choerodon.notify.infra.enums.LevelType;
import io.choerodon.notify.infra.enums.WebHookTypeEnum;
import io.choerodon.notify.infra.feign.BaseFeignClient;
import io.choerodon.notify.infra.mapper.*;
import io.choerodon.swagger.notify.NotifyBusinessTypeScanData;
import io.choerodon.web.util.PageableHelper;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class SendSettingServiceImpl implements SendSettingService {

    public static final String SEND_SETTING_DOES_NOT_EXIST = "error.send.setting.not.exist";
    public static final String SEND_SETTING_UPDATE_EXCEPTION = "error.send.setting.update";
    public static final String RESOURCE_DELETE_CONFIRMATION = "resourceDeleteConfirmation";
    private static final String PROJECT = "project";
    private static final String ORGANIZATION = "organization";
    private static final String ORG_MANAGEMENT = "org-management";
    private static final String DEPLOYMENT_RESOURCES_NOTICE = "deployment-resources-notice";
    private static final String AGILE = "AGILE";
    private static final String ADD_OR_IMPORT_USER = "add-or-import-user";
    private static final String ISSUE_STATUS_CHANGE_NOTICE = "issue-status-change-notice";
    private static final String PRO_MANAGEMENT = "pro-management";
    //资源相关通知
    private static final String CREATE_RESOURCE_FAILED = "createResourceFailed";

    private SendSettingMapper sendSettingMapper;
    private SendSettingCategoryMapper sendSettingCategoryMapper;
    private TemplateMapper templateMapper;
    private MessageSettingMapper messageSettingMapper;
    private MessageSettingTargetUserMapper messageSettingTargetUserMapper;
    private MessageSettingService messageSettingService;
    private BaseFeignClient baseFeignClient;

    public SendSettingServiceImpl(SendSettingMapper sendSettingMapper,
                                  SendSettingCategoryMapper sendSettingCategoryMapper,
                                  TemplateMapper templateMapper,
                                  MessageSettingMapper messageSettingMapper,
                                  MessageSettingTargetUserMapper messageSettingTargetUserMapper,
                                  MessageSettingService messageSettingService,
                                  BaseFeignClient baseFeignClient) {
        this.sendSettingMapper = sendSettingMapper;
        this.sendSettingCategoryMapper = sendSettingCategoryMapper;
        this.templateMapper = templateMapper;
        this.messageSettingMapper = messageSettingMapper;
        this.messageSettingTargetUserMapper = messageSettingTargetUserMapper;
        this.messageSettingService = messageSettingService;
        this.baseFeignClient = baseFeignClient;
    }

    @Override
    public SendSettingVO query(String code) {
        SendSettingDTO sendSettingDTO = new SendSettingDTO();
        sendSettingDTO.setCode(code);
        sendSettingDTO = sendSettingMapper.selectOne(sendSettingDTO);
        if (sendSettingDTO == null) {
            throw new CommonException(SEND_SETTING_DOES_NOT_EXIST);
        }
        SendSettingVO sendSetting = new SendSettingVO();
        BeanUtils.copyProperties(sendSettingDTO, sendSetting);
        Template template = new Template();
        template.setSendSettingCode(code);
        //如果这个消息为部署资源通知，则还要加上json的的模板
        SendSettingCategoryDTO condition = new SendSettingCategoryDTO();
        condition.setCode(sendSettingDTO.getCategoryCode());
        SendSettingCategoryDTO sendSettingCategoryDTO = sendSettingCategoryMapper.selectOne(condition);
        List<Template> templateList = new ArrayList<>();
        List<Template> otherTemplateList = templateMapper.select(template);
        templateList.addAll(otherTemplateList);
        if (DEPLOYMENT_RESOURCES_NOTICE.equals(sendSettingCategoryDTO.getCode())) {
            template.setSendSettingCode(CREATE_RESOURCE_FAILED);
            List<Template> jsonTemplates = templateMapper.select(template);
            templateList.addAll(jsonTemplates);
        }
        sendSetting.setTemplates(templateList);
        return sendSetting;
    }

    @Override
    public void createByScan(Set<NotifyBusinessTypeScanData> businessTypes) {
        businessTypes.forEach(t -> {
            SendSettingDTO sendSettingDTO = new SendSettingDTO();
            BeanUtils.copyProperties(t, sendSettingDTO);
            SendSettingDTO query = sendSettingMapper.selectOne(new SendSettingDTO(sendSettingDTO.getCode()));
            if (query == null) {
                sendSettingDTO.setEdit(sendSettingDTO.getAllowConfig());
                sendSettingMapper.insertSelective(sendSettingDTO);
            } else {
                query.setEdit(sendSettingDTO.getAllowConfig());
                query.setAllowConfig(sendSettingDTO.getAllowConfig());
                query.setName(sendSettingDTO.getName());
                query.setDescription(sendSettingDTO.getDescription());
                query.setLevel(sendSettingDTO.getLevel());
                query.setCategoryCode(sendSettingDTO.getCategoryCode());
                query.setPmEnabledFlag(sendSettingDTO.getPmEnabledFlag());
                query.setEmailEnabledFlag(sendSettingDTO.getEmailEnabledFlag());
                query.setSmsEnabledFlag(sendSettingDTO.getSmsEnabledFlag());
                query.setWebhookOtherEnabledFlag(sendSettingDTO.getWebhookOtherEnabledFlag());
                query.setWebhookJsonEnabledFlag(sendSettingDTO.getWebhookJsonEnabledFlag());
                sendSettingMapper.updateByPrimaryKeySelective(query);
            }

            if (t.getLevel().equals(Level.PROJECT.getValue()) && !t.getNotifyType().equals(ServiceNotifyType.DEFAULT_NOTIFY.getTypeName())) {
                updateMsgSetting(t);
            }
        });
    }

    @Override
    public List<SendSettingDetailTreeDTO> queryByLevelAndAllowConfig(String level, boolean allowConfig) {
        if (level != null) {
            CommonValidator.validatorLevel(level);
        }
        List<SendSettingDetailDTO> list = sendSettingMapper.queryByLevelAndAllowConfig(level, allowConfig);
        List<SendSettingDetailTreeDTO> sendSettingDetailTreeDTOS = new ArrayList<>();

        Map<String, Set<String>> categoryMap = new HashMap<>();
        categoryMap.put(ResourceType.valueOf(level.toUpperCase()).value(), new HashSet<>());
        for (SendSettingDetailDTO sendSettingDTO : list) {
            Set<String> categoryCodes = categoryMap.get(sendSettingDTO.getLevel());
            if (categoryCodes != null) {
                categoryCodes.add(sendSettingDTO.getCategoryCode());
            }
        }
        getSecondSendSettingDetailTreeDTOS(categoryMap, sendSettingDetailTreeDTOS, list);


        return sendSettingDetailTreeDTOS.stream().filter(s -> (s.getEmailTemplateId() != null || s.getPmTemplateId() != null || s.getSmsTemplateId() != null) || s.getParentId() == 0)
                .collect(Collectors.toList());
    }

    private void getSecondSendSettingDetailTreeDTOS(Map<String, Set<String>> categoryMap, List<SendSettingDetailTreeDTO> sendSettingDetailTreeDTOS, List<SendSettingDetailDTO> sendSettingDetailDTOS) {
        int i = 1;
        for (String level : categoryMap.keySet()) {
            for (String categoryCode : categoryMap.get(level)) {
                SendSettingDetailTreeDTO sendSettingDetailTreeDTO = new SendSettingDetailTreeDTO();
                sendSettingDetailTreeDTO.setParentId(0L);

                SendSettingCategoryDTO categoryDTO = new SendSettingCategoryDTO();
                categoryDTO.setCode(categoryCode);
                categoryDTO = sendSettingCategoryMapper.selectOne(categoryDTO);
                sendSettingDetailTreeDTO.setName(categoryDTO.getName());
                sendSettingDetailTreeDTO.setSequenceId((long) i);
                sendSettingDetailTreeDTO.setCode(categoryDTO.getCode());
                //防止被过滤掉
                sendSettingDetailTreeDTO.setEmailTemplateId(0L);
                sendSettingDetailTreeDTOS.add(sendSettingDetailTreeDTO);
                int secondParentId = i;
                i = i + 1;

                i = getThirdSendSettingDetailTreeDTOS(sendSettingDetailDTOS, level, categoryCode, secondParentId, sendSettingDetailTreeDTOS, i);

            }
        }
    }

    private int getThirdSendSettingDetailTreeDTOS(List<SendSettingDetailDTO> sendSettingDetailDTOS, String level, String categoryCode, Integer secondParentId, List<SendSettingDetailTreeDTO> sendSettingDetailTreeDTOS, Integer i) {
        for (SendSettingDetailDTO sendSettingDetailDTO : sendSettingDetailDTOS) {
            if (sendSettingDetailDTO.getLevel().equals(level) && sendSettingDetailDTO.getCategoryCode().equals(categoryCode)) {
                SendSettingDetailTreeDTO sendSettingDetailTreeDTO = new SendSettingDetailTreeDTO();
                BeanUtils.copyProperties(sendSettingDetailDTO, sendSettingDetailTreeDTO);
                sendSettingDetailTreeDTO.setParentId((long) secondParentId);
                sendSettingDetailTreeDTO.setSequenceId((long) i);
                sendSettingDetailTreeDTOS.add(sendSettingDetailTreeDTO);
                i = i + 1;
            }
        }
        return i;
    }


    @Override
    public void delete(Long id) {
        if (sendSettingMapper.selectByPrimaryKey(id) == null) {
            throw new CommonException(SEND_SETTING_DOES_NOT_EXIST);
        }
        sendSettingMapper.deleteByPrimaryKey(id);
    }

    @Override
    public PageInfo<MessageServiceVO> pagingAll(String messageType, String introduce, Boolean enabled, Boolean allowConfig, String params, Pageable pageable, String firstCode, String secondCode) {
        return PageHelper.startPage(pageable.getPageNumber(), pageable.getPageSize(), PageableHelper.getSortSql(pageable.getSort())).doSelectPageInfo(
                () -> sendSettingMapper.doFTR(messageType, introduce, firstCode, secondCode, enabled, allowConfig, params));
    }


    @Override
    public MessageServiceVO enabled(String code) {
        SendSettingDTO enabledDTO = new SendSettingDTO();
        enabledDTO.setCode(code);
        enabledDTO = sendSettingMapper.selectOne(enabledDTO);
        if (enabledDTO == null) {
            throw new CommonException(SEND_SETTING_DOES_NOT_EXIST);
        }
        if (!enabledDTO.getEnabled()) {
            enabledDTO.setEnabled(true);
            if (sendSettingMapper.updateByPrimaryKeySelective(enabledDTO) != 1) {
                throw new CommonException("error.send.setting.enabled");
            }
        }
        return getMessageServiceVO(enabledDTO);
    }

    @Override
    public MessageServiceVO disabled(String code) {
        SendSettingDTO disabledDTO = new SendSettingDTO();
        disabledDTO.setCode(code);
        disabledDTO = sendSettingMapper.selectOne(disabledDTO);
        if (disabledDTO == null) {
            throw new CommonException(SEND_SETTING_DOES_NOT_EXIST);
        }
        if (disabledDTO.getEnabled()) {
            disabledDTO.setEnabled(false);
            if (sendSettingMapper.updateByPrimaryKeySelective(disabledDTO) != 1) {
                throw new CommonException("error.send.setting.disabled");
            }
        }
        return getMessageServiceVO(disabledDTO);
    }

    /**
     * 根据 notify_send_setting{@link SendSettingDTO}
     * 获取 MessageServiceVO {@link MessageServiceVO}
     *
     * @param sendSetting {@link SendSettingDTO}
     * @return {@link MessageServiceVO}
     */
    private MessageServiceVO getMessageServiceVO(SendSettingDTO sendSetting) {
        return new MessageServiceVO()
                .setId(sendSetting.getId())
                .setMessageType(sendSetting.getName())
                .setIntroduce(sendSetting.getDescription())
                .setLevel(sendSetting.getLevel())
                .setAllowConfig(sendSetting.getAllowConfig())
                .setEnabled(sendSetting.getEnabled())
                .setObjectVersionNumber(sendSetting.getObjectVersionNumber());
    }

    @Override
    public List<MsgServiceTreeVO> getMsgServiceTree() {
        List<SendSettingDTO> sendSettingDTOS = sendSettingMapper.selectAll();
        List<MsgServiceTreeVO> msgServiceTreeVOS = new ArrayList<>();
        MsgServiceTreeVO msgServiceTreeVO1 = new MsgServiceTreeVO();
        msgServiceTreeVO1.setParentId(0L);
        msgServiceTreeVO1.setId(1L);
        msgServiceTreeVO1.setName(LevelType.SITE.value());
        msgServiceTreeVO1.setCode(ResourceType.SITE.value());
        msgServiceTreeVOS.add(msgServiceTreeVO1);

        MsgServiceTreeVO msgServiceTreeVO2 = new MsgServiceTreeVO();
        msgServiceTreeVO2.setParentId(0L);
        msgServiceTreeVO2.setId(2L);
        msgServiceTreeVO2.setName(LevelType.ORGANIZATION.value());
        msgServiceTreeVO2.setCode(ResourceType.ORGANIZATION.value());
        msgServiceTreeVOS.add(msgServiceTreeVO2);

        MsgServiceTreeVO msgServiceTreeVO3 = new MsgServiceTreeVO();
        msgServiceTreeVO3.setParentId(0L);
        msgServiceTreeVO3.setId(3L);
        msgServiceTreeVO3.setName(LevelType.PROJECT.value());
        msgServiceTreeVO3.setCode(ResourceType.PROJECT.value());
        msgServiceTreeVOS.add(msgServiceTreeVO3);

        Map<String, Set<String>> categoryMap = new HashMap<>();
        categoryMap.put(ResourceType.SITE.value(), new HashSet<>());
        categoryMap.put(ResourceType.ORGANIZATION.value(), new HashSet<>());
        categoryMap.put(ResourceType.PROJECT.value(), new HashSet<>());
        for (SendSettingDTO sendSettingDTO : sendSettingDTOS) {
            Set<String> categoryCodes = categoryMap.get(sendSettingDTO.getLevel());
            if (categoryCodes != null) {
                categoryCodes.add(sendSettingDTO.getCategoryCode());
            }
        }
        getSecondMsgServiceTreeVOS(categoryMap, msgServiceTreeVOS, sendSettingDTOS);


        return msgServiceTreeVOS;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SendSettingDTO updateSendSetting(Long id, SendSettingDTO updateDTO) {
        SendSettingDTO oldSetting = sendSettingMapper.selectByPrimaryKey(id);
        updateDTO.setId(id);
        if (sendSettingMapper.updateByPrimaryKeySelective(updateDTO) != 1) {
            throw new CommonException(SEND_SETTING_UPDATE_EXCEPTION);
        }
        compareAndUpdateProjectSetting(oldSetting, updateDTO);
        return sendSettingMapper.selectByPrimaryKey(id);
    }

    /**
     * 比较更新内容，（取消勾选发送方式时，把项目的设置也取消掉）
     *
     * @param oldSetting
     * @param updateDTO
     */
    private void compareAndUpdateProjectSetting(SendSettingDTO oldSetting, SendSettingDTO updateDTO) {
        if (Boolean.TRUE.equals(oldSetting.getPmEnabledFlag()) && Boolean.FALSE.equals(updateDTO.getPmEnabledFlag())) {
            messageSettingService.disableNotifyTypeByCodeAndType(updateDTO.getCode(), NotifyType.PM.getValue());
        }
        if (Boolean.TRUE.equals(oldSetting.getEmailEnabledFlag()) && Boolean.FALSE.equals(updateDTO.getEmailEnabledFlag())) {
            messageSettingService.disableNotifyTypeByCodeAndType(updateDTO.getCode(), NotifyType.EMAIL.getValue());
        }
        if (Boolean.TRUE.equals(oldSetting.getSmsEnabledFlag()) && Boolean.FALSE.equals(updateDTO.getSmsEnabledFlag())) {
            messageSettingService.disableNotifyTypeByCodeAndType(updateDTO.getCode(), NotifyType.SMS.getValue());
        }
    }

    private void getSecondMsgServiceTreeVOS(Map<String, Set<String>> categoryMap, List<MsgServiceTreeVO> msgServiceTreeVOS, List<SendSettingDTO> sendSettingDTOS) {
        int i = 4;
        for (String level : categoryMap.keySet()) {
            for (String categoryCode : categoryMap.get(level)) {
                MsgServiceTreeVO msgServiceTreeVO = new MsgServiceTreeVO();
                if (level.equals(ResourceType.SITE.value())) {
                    msgServiceTreeVO.setParentId(1L);
                } else if (level.equals(ResourceType.ORGANIZATION.value())) {
                    msgServiceTreeVO.setParentId(2L);
                } else {
                    msgServiceTreeVO.setParentId(3L);
                }

                SendSettingCategoryDTO categoryDTO = new SendSettingCategoryDTO();
                categoryDTO.setCode(categoryCode);
                categoryDTO = sendSettingCategoryMapper.selectOne(categoryDTO);
                msgServiceTreeVO.setName(categoryDTO.getName());
                msgServiceTreeVO.setId((long) i);
                msgServiceTreeVO.setCode(categoryDTO.getCode());
                msgServiceTreeVOS.add(msgServiceTreeVO);
                int secondParentId = i;
                i = i + 1;

                i = getThirdMsgServiceTreeVOS(sendSettingDTOS, level, categoryCode, secondParentId, msgServiceTreeVOS, i);

            }
        }
    }

    private int getThirdMsgServiceTreeVOS(List<SendSettingDTO> sendSettingDTOS, String level, String categoryCode, Integer secondParentId, List<MsgServiceTreeVO> msgServiceTreeVOS, Integer i) {
        for (SendSettingDTO sendSettingDTO : sendSettingDTOS) {
            if (sendSettingDTO.getLevel().equals(level) && sendSettingDTO.getCategoryCode().equals(categoryCode)) {
                MsgServiceTreeVO treeVO = new MsgServiceTreeVO();
                treeVO.setParentId((long) secondParentId);
                treeVO.setId((long) i);
                treeVO.setName(sendSettingDTO.getName());
                treeVO.setEnabled(sendSettingDTO.getEnabled());
                treeVO.setCode(sendSettingDTO.getCode());
                msgServiceTreeVOS.add(treeVO);
                i = i + 1;
            }
        }
        return i;
    }

    @Override
    public WebHookVO.SendSetting getUnderProject(Long sourceId, String sourceLevel, String name, String description, String type) {
        WebHookVO.SendSetting sendSetting = new WebHookVO.SendSetting();
        //1.获取WebHook 发送设置可选集合(启用,且启用WebHook的发送设置)
        SendSettingDTO condition = new SendSettingDTO();
        condition.setLevel(sourceLevel);
        condition.setName(name);
        condition.setDescription(description);
        condition.setEnabled(true);
        if (!Objects.isNull(type)) {
            if (WebHookTypeEnum.DINGTALK.getValue().equals(type) || WebHookTypeEnum.WECHAT.getValue().equals(type)) {
                condition.setWebhookOtherEnabledFlag(true);
            }
            if (WebHookTypeEnum.JSON.getValue().equals(type)) {
                condition.setWebhookJsonEnabledFlag(true);
            }
        }
        List<SendSettingDTO> sendSettingSelection = sendSettingMapper.pageSendSettingByCondition(condition, type);
        if (CollectionUtils.isEmpty(sendSettingSelection)) {
            return sendSetting;
        }
        //2.获取发送设置类别集合
        Set<SendSettingCategoryDTO> sendSettingCategorySelection = sendSettingCategoryMapper.selectByCodeSet(sendSettingSelection.stream().map(SendSettingDTO::getCategoryCode).collect(Collectors.toSet()));
        //普通敏捷项目只保留三类通知
        List<SendSettingDTO> settingDTOS = new ArrayList<>();
        Set<SendSettingCategoryDTO> sendSettingCategoryDTOS = new HashSet<>();

        if (PROJECT.equals(sourceLevel)) {
            ProjectDTO projectDTO = baseFeignClient.queryProjectById(sourceId).getBody();
            if (!Objects.isNull(projectDTO) && AGILE.equals(projectDTO.getCategory())) {
                Set<SendSettingCategoryDTO> settingCategoryDTOS = sendSettingCategorySelection.stream().filter(sendSettingCategoryDTO ->
                        ADD_OR_IMPORT_USER.equals(sendSettingCategoryDTO.getCode())
                                || ISSUE_STATUS_CHANGE_NOTICE.equals(sendSettingCategoryDTO.getCode())
                                || PRO_MANAGEMENT.equals(sendSettingCategoryDTO.getCode())

                ).collect(Collectors.toSet());
                List<SendSettingDTO> sendSettingDTOS = sendSettingSelection.stream().filter(sendSettingDTO ->
                        ADD_OR_IMPORT_USER.equals(sendSettingDTO.getCategoryCode())
                                || ISSUE_STATUS_CHANGE_NOTICE.equals(sendSettingDTO.getCategoryCode()) ||
                                PRO_MANAGEMENT.equals(sendSettingDTO.getCategoryCode())).collect(Collectors.toList());
                return sendSetting.setSendSettingSelection(new HashSet<>(sendSettingDTOS)).setSendSettingCategorySelection(new HashSet<>(settingCategoryDTOS));
            }
        }
        if (ORGANIZATION.equals(sourceLevel)) {
            //只展示项目层的消息
            Set<SendSettingCategoryDTO> settingCategoryDTOS = sendSettingCategorySelection.stream().filter(sendSettingCategoryDTO ->
                    ORG_MANAGEMENT.equals(sendSettingCategoryDTO.getCode())).collect(Collectors.toSet());
            List<SendSettingDTO> sendSettingDTOS = sendSettingSelection.stream().filter(sendSettingDTO ->
                    ORG_MANAGEMENT.equals(sendSettingDTO.getCategoryCode())).collect(Collectors.toList());
            return sendSetting.setSendSettingSelection(new HashSet<>(sendSettingDTOS)).setSendSettingCategorySelection(new HashSet<>(settingCategoryDTOS));
        }
        //3.构造返回数据
        return sendSetting.setSendSettingSelection(new HashSet<>(sendSettingSelection)).setSendSettingCategorySelection(new HashSet<>(sendSettingCategorySelection));
    }

    @Override
    public MessageServiceVO allowConfiguration(Long id) {
        SendSettingDTO allowDTO = sendSettingMapper.selectByPrimaryKey(id);
        if (allowDTO == null) {
            throw new CommonException(SEND_SETTING_DOES_NOT_EXIST);
        }
        if (!allowDTO.getAllowConfig()) {
            allowDTO.setAllowConfig(true);
            if (sendSettingMapper.updateByPrimaryKeySelective(allowDTO) != 1) {
                throw new CommonException("error.send.setting.allow.configuration");
            }
        }
        return getMessageServiceVO(allowDTO);
    }

    @Override
    public MessageServiceVO forbiddenConfiguration(Long id) {
        SendSettingDTO forbiddenDTO = sendSettingMapper.selectByPrimaryKey(id);
        if (forbiddenDTO == null) {
            throw new CommonException(SEND_SETTING_DOES_NOT_EXIST);
        }
        if (forbiddenDTO.getAllowConfig()) {
            forbiddenDTO.setAllowConfig(false);
            if (sendSettingMapper.updateByPrimaryKeySelective(forbiddenDTO) != 1) {
                throw new CommonException("error.send.setting.forbidden.configuration");
            }
        }
        return getMessageServiceVO(forbiddenDTO);
    }

    @Override
    public SendSettingDTO queryByCode(String code) {
        SendSettingDTO sendSettingDTO = new SendSettingDTO();
        sendSettingDTO.setCode(code);
        return sendSettingMapper.selectOne(sendSettingDTO);
    }

    @Override
    public Boolean checkResourceDeleteEnabled() {
        SendSettingDTO record = new SendSettingDTO();
        record.setCode(RESOURCE_DELETE_CONFIRMATION);
        SendSettingDTO sendSettingDTO = sendSettingMapper.selectOne(record);
        return sendSettingDTO.getEnabled();
    }

    /**
     * 更新MessageSetting
     *
     * @param typeScanData
     */
    private void updateMsgSetting(NotifyBusinessTypeScanData typeScanData) {
        MessageSettingDTO queryDTO = messageSettingMapper.queryByCodeWithoutProjectId(typeScanData.getCode());
        if (queryDTO == null) {
            MessageSettingDTO messageSettingDTO = new MessageSettingDTO();
            BeanUtils.copyProperties(typeScanData, messageSettingDTO);
            messageSettingDTO.setEmailEnable(typeScanData.getProEmailEnabledFlag());
            messageSettingDTO.setPmEnable(typeScanData.getProPmEnabledFlag());
            messageSettingMapper.insertSelective(messageSettingDTO);
            if (typeScanData.getTargetUserType().length > 0) {
                for (String targetUserType : typeScanData.getTargetUserType()) {
                    TargetUserDTO targetUserDTO = new TargetUserDTO();
                    targetUserDTO.setMessageSettingId(messageSettingDTO.getId());
                    targetUserDTO.setType(targetUserType);
                    messageSettingTargetUserMapper.insertSelective(targetUserDTO);
                }
            }
        } else {
            queryDTO.setPmEnable(typeScanData.getProPmEnabledFlag());
            queryDTO.setEmailEnable(typeScanData.getProEmailEnabledFlag());
            queryDTO.setNotifyType(typeScanData.getNotifyType());
            if (messageSettingMapper.updateByPrimaryKeySelective(queryDTO) != 1) {
                throw new CommonException("error.insert.message.setting");
            }
            updateTargetUser(typeScanData, queryDTO.getId());
        }
    }

    /**
     * 更新targetUser
     *
     * @param typeScanData
     * @param mgsSettingId
     */
    private void updateTargetUser(NotifyBusinessTypeScanData typeScanData, Long mgsSettingId) {
        List<String> oldTypeList = messageSettingTargetUserMapper.listByMsgSettingId(mgsSettingId).stream().map(TargetUserDTO::getType).collect(Collectors.toList());
        List<String> newTypeList = Arrays.asList(typeScanData.getTargetUserType());
        List<String> typeList = new ArrayList<>(newTypeList);
        if (oldTypeList != null) {
            newTypeList.forEach(type -> {
                if (oldTypeList.contains(type)) {
                    oldTypeList.remove(type);
                    typeList.remove(type);
                }
            });
        }

        if (oldTypeList != null) {
            oldTypeList.forEach(oldType -> {
                TargetUserDTO targetUserDTO = new TargetUserDTO();
                targetUserDTO.setMessageSettingId(mgsSettingId);
                targetUserDTO.setType(oldType);
                messageSettingTargetUserMapper.delete(targetUserDTO);
            });
        }

        typeList.forEach(newType -> {
            TargetUserDTO targetUserDTO = new TargetUserDTO();
            targetUserDTO.setMessageSettingId(mgsSettingId);
            targetUserDTO.setType(newType);
            messageSettingTargetUserMapper.insertSelective(targetUserDTO);
        });
    }
}
