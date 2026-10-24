package com.litedata.catalog;

import com.litedata.governance.JdbcMetadataCollector;
import com.litedata.service.QueryPage;
import com.litedata.store.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AssetCatalogService {
    public static final Map<String,String> STATUSES=Collections.unmodifiableMap(new LinkedHashMap<>(Map.of(
            "ACTIVE","活动","ARCHIVED","已归档","MISSING","未发现","PUBLISHED","已发布","UNPUBLISHED","未发布")));
    public static final Map<String,String> SENSITIVITIES=Map.of("INTERNAL","内部","PUBLIC","公开","CONFIDENTIAL","敏感");
    public record Stats(long total,long tables,long models,long metrics,long services) {}
    public record QualityLink(long id,String name,String status) {
        public String label() { return switch(status) {
            case "PASSED" -> "通过"; case "WARNING" -> "警告"; case "FAILED" -> "数据异常";
            case "ERROR" -> "执行错误"; case "RUNNING" -> "运行中"; default -> "未执行";
        }; }
    }
    private final AssetCatalogMapper mapper;
    private final MetadataMapper metadata;
    private final PlatformMapper platform;
    public AssetCatalogService(AssetCatalogMapper mapper,MetadataMapper metadata,PlatformMapper platform) {
        this.mapper=mapper; this.metadata=metadata; this.platform=platform;
    }
    public long count(AssetFilters filters) { return mapper.count(filters); }
    public List<AssetEntry> list(AssetFilters filters,QueryPage page) { return mapper.page(filters,page.pageSize(),page.offset()); }
    public Stats stats() { return mapper.stats(); }
    public AssetEntry require(String type,long id) {
        AssetType parsed;
        try { parsed=AssetType.valueOf(type); }
        catch(IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND,"资产不存在"); }
        var asset=id<1?null:mapper.entry(parsed,id);
        if(asset==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"资产不存在或原资源已移除");
        return asset;
    }
    public AssetProfile profile(AssetEntry asset) { return mapper.profile(asset.getType(),asset.getResourceId()); }
    public List<JdbcMetadataCollector.Column> columns(AssetEntry asset) {
        return asset.getMetadataId()==null?List.of():metadata.columns(asset.getMetadataId());
    }
    public long qualityCount(AssetEntry asset) {
        return asset.getTableName().isBlank()?0:mapper.qualityCount(asset.getScopeKey(),asset.getTableName());
    }
    public List<QualityLink> qualityLinks(AssetEntry asset) {
        return asset.getTableName().isBlank()?List.of():mapper.qualityLinks(asset.getScopeKey(),asset.getTableName());
    }
    @Transactional
    public void save(String type,long id,AssetProfile input,String actor) {
        var asset=require(type,id);
        input.setBusinessName(text(input.getBusinessName(),100,"业务名称"));
        input.setSubjectArea(text(input.getSubjectArea(),100,"主题域"));
        input.setOwner(text(input.getOwner(),100,"负责人"));
        input.setDescription(text(input.getDescription(),2000,"业务说明"));
        input.setUpdateFrequency(text(input.getUpdateFrequency(),100,"更新频率"));
        if(!SENSITIVITIES.containsKey(Objects.toString(input.getSensitivity(),""))) throw new IllegalArgumentException("请选择有效的敏感级别");
        var tags=new LinkedHashSet<String>();
        for(String tag:text(input.getTags(),500,"标签").replace('，',',').split(",")) {
            String value=tag.trim();
            if(value.length()>50) throw new IllegalArgumentException("单个标签最长50字符");
            if(!value.isEmpty()) tags.add(value);
        }
        if(tags.size()>10) throw new IllegalArgumentException("最多填写10个标签");
        input.setTags(String.join(",",tags));
        if(input.getVersion()<0) throw new IllegalArgumentException("资产描述版本无效，请重新打开编辑页面");
        if(input.getVersion()==0) {
            try { mapper.insert(asset.getType(),id,input,actor); }
            catch(DuplicateKeyException e) { throw stale(); }
        } else if(mapper.update(asset.getType(),id,input,actor)!=1) throw stale();
        platform.audit(actor,"ASSET_PROFILE_SAVE",type+":"+id,"SUCCESS");
    }
    private static String text(String value,int limit,String label) {
        String normalized=Objects.toString(value,"").trim();
        if(normalized.length()>limit) throw new IllegalArgumentException(label+"最长"+limit+"字符");
        return normalized;
    }
    private static IllegalArgumentException stale() { return new IllegalArgumentException("资产描述已被其他人更新，请重新打开编辑页面后保存"); }
}
