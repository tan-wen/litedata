package com.litedata.governance;

import com.litedata.service.QueryPage;
import com.litedata.store.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.SQLException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MetadataService {
    private final MetadataMapper mapper;
    private final PlatformMapper platform;
    private final JdbcMetadataCollector collector;
    public MetadataService(MetadataMapper mapper, PlatformMapper platform, JdbcMetadataCollector collector) {
        this.mapper=mapper; this.platform=platform; this.collector=collector;
    }
    public long count(String keyword,String layer) { return mapper.count(keyword(keyword),layer(layer)); }
    public List<MetadataTable> list(String keyword,String layer,QueryPage page) {
        return mapper.page(keyword(keyword),layer(layer),page.pageSize(),page.offset());
    }
    public MetadataTable require(long id) {
        var table=mapper.table(id);
        if(table==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"元数据不存在");
        return table;
    }
    public List<JdbcMetadataCollector.Column> columns(long id) { require(id); return mapper.columns(id); }
    @Transactional(rollbackFor=Exception.class)
    public int collect(Long sourceId,String actor) {
        if(sourceId!=null && sourceId<1) throw new IllegalArgumentException("请选择有效的数据源");
        String scope=sourceId==null?"doris":"source:"+sourceId;
        mapper.ensureScope(scope); mapper.lockScope(scope);
        JdbcMetadataCollector.Snapshot snapshot;
        try { snapshot=collector.collect(sourceId); }
        catch(SQLException exception) { throw new IllegalArgumentException("元数据采集失败，请检查连接及结构读取权限；原有快照已保留"); }
        // Missing flags are changed only after a complete, successful read of this scope.
        mapper.markMissing(scope);
        for(var table:snapshot.tables()) {
            var saved=mapper.find(scope,snapshot.catalog(),table.name());
            if(saved==null) saved=new MetadataTable();
            saved.setScopeKey(scope); saved.setCatalogName(snapshot.catalog()); saved.setTableName(table.name());
            saved.setComment(table.comment()); saved.setSchemaHash(hash(table));
            if(saved.getId()==null) mapper.insert(saved); else mapper.update(saved);
            mapper.deleteColumns(saved.getId());
            for(var column:table.columns()) mapper.insertColumn(saved.getId(),column);
        }
        platform.audit(actor,"METADATA_COLLECT",scope,"SUCCESS tables="+snapshot.tables().size());
        return snapshot.tables().size();
    }
    private static String hash(JdbcMetadataCollector.Table table) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(table.toString().getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static String keyword(String value) {
        if(value==null || value.length()>100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"搜索词最长100字符");
        return value.trim().toLowerCase(Locale.ROOT);
    }
    private static String layer(String value) {
        if(!List.of("","ODS","DWD","DWS","ADS").contains(value)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"无效分层");
        return value;
    }
}
