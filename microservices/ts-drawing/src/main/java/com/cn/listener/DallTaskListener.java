package com.cn.listener;

import com.alibaba.fastjson.JSONObject;
import com.cn.common.DallCommon;
import com.cn.common.PoolCommon;
import com.cn.constant.DrawingConstant;
import com.cn.constant.DrawingStatusConstant;
import com.cn.entity.TsGenerateDrawing;
import com.cn.enums.DrawingTypeEnum;
import com.cn.enums.FileEnum;
import com.cn.mapper.TsGenerateDrawingMapper;
import com.cn.model.DallModel;
import com.cn.structure.TaskStructure;
import com.cn.utils.StringUtils;
import com.cn.utils.UploadUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Transactional(rollbackFor = Exception.class)
@RequiredArgsConstructor
@SuppressWarnings("all")
@Configuration
public class DallTaskListener {


    private final RedisTemplate<String, Object> redisTemplate;

    private final WebClient.Builder webClient;

    private final ThreadPoolExecutor threadPoolExecutor;

    private final UploadUtil uploadUtil;

    private final TsGenerateDrawingMapper tsGenerateDrawingMapper;
    private Semaphore semaphore;

    @EventListener(ApplicationReadyEvent.class)
    public void dallListening() {
        semaphore = new Semaphore(PoolCommon.STRUCTURE.getDallConcurrent());
        threadPoolExecutor.execute(() -> {
            while (true) {
                try {
                    semaphore.acquire();
                    //每三秒从队列中获取数据
                    final TaskStructure ts = (TaskStructure) redisTemplate.opsForList().rightPop(DrawingConstant.DALL_TASK_QUEUE, 2, TimeUnit.SECONDS);
                    if (ts != null) {
                        this.handleDallGenerate(ts.getExtra(), ts.getTaskId());
                    }
                } catch (Exception e) {
                    log.error("DALL绘图异常 原因:{} 位置:{}", e.getMessage(), e.getClass());
                } finally {
                    semaphore.release(); // 释放信号量许可
                }

            }
        });
    }


    private void handleDallGenerate(final Object o, final String taskId) {
        final DallModel model = (DallModel) o;
        //dall有类型 文生图 图生图 局部绘图
        //根据传参区分 具体操作
        final Map<String, Object> map = new HashMap<>();
//        final MultiValueMap<String, Object> map = new LinkedMultiValueMap<>();
        map.put("prompt", model.getPrompt());
        if (StringUtils.notEmpty(model.getMask())) {
            map.put("mask", model.getMask());
        }
        if (StringUtils.notEmpty(model.getImage())) {
            map.put("image", model.getImage());
        }
        map.put("model", model.getModel());
        map.put("n", model.getN());
        map.put("size", model.getSize());
        String uri = "/images/generations";
//        if (StringUtils.notEmpty(model.getImage()) && StringUtils.notEmpty(model.getMask())) {
//            //局部绘图
//            uri = "/images/edits";
//        } else if (StringUtils.notEmpty(model.getImage()) && !StringUtils.notEmpty(model.getMask())) {
//            // 图生图
//            map.remove("prompt");
//            uri = "/images/variations";
//        }
        //设置执行任务
        final TaskStructure taskStructure = new TaskStructure().setDrawingType(DrawingTypeEnum.DALL.getDec()).setTaskId(taskId).setPrompt(model.getPrompt());

        final TsGenerateDrawing tsGenerateDrawing = new TsGenerateDrawing().setGenerateDrawingId(taskId);
        try {
            //设置当前任务
            redisTemplate.opsForHash().put(DrawingConstant.DALL_EXECUTION, taskId, taskId);

            final String block = webClient.baseUrl(DallCommon.STRUCTURE.getRequestUrl())
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + DallCommon.pollGetKey())
                    .codecs(item -> item.defaultCodecs().maxInMemorySize(20 * 1024 * 1024)).build()
                    .post()
                    .uri(uri)
                    .body(BodyInserters.fromValue(map))
                    .retrieve()
                    .bodyToMono(String.class).block();
            final String string = JSONObject.parseObject(block).getJSONArray("data").getJSONObject(0).getString("url");
            //上传至阿里云
            final String drawingUrl = uploadUtil.uploadImageFromUrl(string, FileEnum.DRAWING.getDec());
            tsGenerateDrawing
                    .setStatus(DrawingStatusConstant.SUCCEED)
                    .setUrl(drawingUrl);
            //设置构建成功
            redisTemplate.opsForValue().set(DrawingConstant.TASK + taskId,
                    taskStructure
                            .setImageUrl(drawingUrl)
                            .setStatus(DrawingStatusConstant.SUCCEED)
                    , 600, TimeUnit.SECONDS);
        } catch (Exception e) {
            tsGenerateDrawing.setStatus(DrawingStatusConstant.DISUSE);
            //设置返回错误结果
            redisTemplate.opsForValue()
                    .set(DrawingConstant.TASK + taskId, taskStructure
                            .setStatus(DrawingStatusConstant.DISUSE), 600, TimeUnit.SECONDS);
        } finally {
            redisTemplate.opsForHash().delete(DrawingConstant.DALL_EXECUTION, taskId);
            tsGenerateDrawingMapper.updateById(tsGenerateDrawing);
        }
    }


}
