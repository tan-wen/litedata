package com.litedata.pipeline;

import com.litedata.data.*;
import com.litedata.governance.JdbcMetadataCollector;
import com.litedata.governance.MetadataService;
import com.litedata.model.*;
import com.litedata.modeling.*;
import com.litedata.scheduling.SchedulePreview;
import com.litedata.store.*;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OdsPipelineService {
    public record PhysicalSettings(String distributionType,String distributionColumns,String partitionType,String partitionColumn,
                                   String partitionUnit,String partitionRangeStyle,String dynamicPartitionProperties,List<ModelPartition> partitions) {
        public static PhysicalSettings defaults() {
            return new PhysicalSettings("HASH","","NONE","","DAY","FIXED",DataModel.DEFAULT_DYNAMIC_PARTITION_PROPERTIES,List.of());
        }
    }
    public record Input(String name,long sourceId,String sourceTable,String watermarkColumn,String targetTable,
                        String cronExpression,List<String> keyColumns,boolean autoBuckets,int buckets,int replicationNum,PhysicalSettings physical) {
        public Input(String name,long sourceId,String sourceTable,String watermarkColumn,String targetTable,
                     String cronExpression,List<String> keyColumns,boolean autoBuckets,int buckets,int replicationNum) {
            this(name,sourceId,sourceTable,watermarkColumn,targetTable,cronExpression,keyColumns,autoBuckets,buckets,replicationNum,PhysicalSettings.defaults());
        }
    }
    public record Mapping(String sourceName,String sourceType,String targetName,String targetType,boolean nullable,
                          boolean keyColumn,boolean keyEligible,String comment) {}
    public record Preview(DataModel model,List<Mapping> fields,List<String> primaryKeys,String sourceHash,
                          String previewHash,String ddl,List<String> warnings,boolean ready,String watermarkColumn,SourceSchemaInspector.Table sourceSchema) {}
    public static class PreviewFailure extends IllegalArgumentException {
        private final Preview fields;
        PreviewFailure(String message,Preview fields) { super(message); this.fields=fields; }
        public Preview fields() { return fields; }
    }
    private final SourceService sources;
    private final SourceSchemaInspector inspector;
    private final ModelMapper models;
    private final ModelingService modeling;
    private final DorisModelGateway gateway;
    private final PipelineService pipelines;
    private final PlatformMapper platform;
    private final MetadataService metadata;
    private final DorisDdl ddl;
    public OdsPipelineService(SourceService sources,SourceSchemaInspector inspector,ModelMapper models,ModelingService modeling,
            DorisModelGateway gateway,PipelineService pipelines,PlatformMapper platform,MetadataService metadata,DorisDdl ddl) {
        this.sources=sources; this.inspector=inspector; this.models=models; this.modeling=modeling; this.gateway=gateway;
        this.pipelines=pipelines; this.platform=platform; this.metadata=metadata; this.ddl=ddl;
    }

    public ModelingService.BackendStatus backendStatus() { return modeling.backendStatus(); }

    public Preview preview(Input input) {
        var source=sources.require(input.sourceId());
        String table=JdbcConnections.identifier(input.sourceTable());
        var schema=inspector.inspect(source.getId(),table);
        String target=input.targetTable()==null || input.targetTable().isBlank()?suggestedTarget(source.getId(),table)
                :JdbcConnections.identifier(input.targetTable()).toLowerCase(Locale.ROOT);
        if(models.modelByTable(target)!=null) throw new IllegalArgumentException("目标已有登记模型，请选择接入已有 ODS 模型，或使用新的目标表名");
        var keys=new ArrayList<String>(input.keyColumns()==null || input.keyColumns().isEmpty()?schema.primaryKeys():input.keyColumns());
        if(keys.size()>100 || new HashSet<>(keys).size()!=keys.size()) throw new IllegalArgumentException("键列不能重复且最多100列");
        var selected=new HashSet<>(keys);
        var sourceNames=schema.columns().stream().map(SourceSchemaInspector.Column::name).toList();
        for(String key:keys) if(!sourceNames.contains(key)) throw new IllegalArgumentException("源表中不存在所选键列："+key);
        // Browser checkboxes submit in source-column order, which may differ from composite PK order.
        keys=new ArrayList<>(selected.size()==schema.primaryKeys().size() && selected.containsAll(schema.primaryKeys())
                ?schema.primaryKeys():sourceNames.stream().filter(selected::contains).toList());
        var fields=new ArrayList<Mapping>();
        var byName=new LinkedHashMap<String,ModelField>();
        var warnings=new ArrayList<String>();
        for(var column:schema.columns()) {
            String type=OdsTypeMapping.convert(source.getDatabaseType(),column);
            boolean eligible=!column.nullable() && !Set.of("STRING","FLOAT","DOUBLE").contains(type);
            boolean key=keys.contains(column.name());
            if(key && !eligible) throw new IllegalArgumentException("键列必须非空且为 Doris 支持的键类型："+column.name());
            var field=new ModelField(); field.setName(column.name().toLowerCase(Locale.ROOT)); field.setDataType(type);
            field.setNullable(column.nullable()); field.setKeyColumn(key); field.setComment(cleanComment(column.comment(),500));
            byName.put(column.name(),field);
            fields.add(new Mapping(column.name(),OdsSchemaPolicy.sourceType(column),field.getName(),type,column.nullable(),key,eligible,field.getComment()));
        }
        for(String key:keys) if(!byName.containsKey(key)) throw new IllegalArgumentException("源表中不存在所选键列："+key);
        if(keys.isEmpty()) warnings.add("源表没有主键，请选择非空、唯一且稳定的业务键后重新预览；系统不扫描业务数据验证唯一性");
        else if(!keys.equals(schema.primaryKeys())) warnings.add("使用了手动选择的业务键，请确认这些字段共同唯一且稳定；系统不扫描业务数据验证唯一性");
        var ordered=new ArrayList<ModelField>();
        for(String key:keys) ordered.add(byName.get(key));
        for(var entry:byName.entrySet()) if(!keys.contains(entry.getKey())) ordered.add(entry.getValue());
        if(!schema.columns().stream().map(SourceSchemaInspector.Column::name).limit(keys.size()).toList().equals(keys))
            warnings.add("Doris 键列会排在目标字段前部；同步仍按字段名称对应，不改变来源字段的读取顺序");
        if(schema.columns().stream().anyMatch(c->!c.name().equals(c.name().toLowerCase(Locale.ROOT))))
            warnings.add("目标字段按 Doris 模型规则使用小写名称，系统会保留对应的来源字段名称进行读取");
        String watermark=Objects.toString(input.watermarkColumn(),"").trim();
        if(watermark.isEmpty()) watermark=schema.columns().stream().filter(c->c.name().equalsIgnoreCase("updated_at"))
                .map(SourceSchemaInspector.Column::name).findFirst().orElse("");
        if(watermark.isEmpty()) warnings.add("请填写有前导索引的非空时间水位列，再重新预览");
        else {
            try(var connection=sources.connect(source)) { SourceTable.inspect(connection,source.getDatabaseType(),table,watermark); }
            catch(SQLException exception) { throw new IllegalArgumentException("无法核对源表水位，请检查连接、字段和索引"); }
        }
        var model=new DataModel(); model.setTableName(target); model.setName(cleanComment("ODS "+source.getName()+"."+table,100));
        model.setSubjectArea("贴源接入"); model.setLayer("ODS"); model.setModelType("UNIQUE");
        model.setDescription(cleanComment(schema.comment(),500)); model.setAutoBuckets(input.autoBuckets()); model.setBuckets(input.buckets());
        model.setReplicationNum(input.replicationNum());
        model.setFields(ordered);
        var settings=input.physical()==null?PhysicalSettings.defaults():input.physical();
        model.setDistributionType(settings.distributionType());
        model.setDistributionColumns(settings.distributionColumns()==null || settings.distributionColumns().isBlank()
                ?String.join(",",keys.stream().map(k->k.toLowerCase(Locale.ROOT)).toList()):settings.distributionColumns());
        model.setPartitionType(settings.partitionType()); model.setPartitionColumn(settings.partitionColumn());
        model.setPartitionUnit(settings.partitionUnit()); model.setPartitionRangeStyle(settings.partitionRangeStyle());
        model.setDynamicPartitionProperties(settings.dynamicPartitionProperties());
        model.setPartitions(settings.partitions()==null?List.of():settings.partitions());
        String sourceHash=OdsSchemaPolicy.sourceHash(source,table,schema);
        try {
        String generated=keys.isEmpty()?"":ddl.generate(model);
        if(!generated.isEmpty()) {
            try {
                var physical=gateway.inspect(target);
                if(!physical.columns().isEmpty()) {
                    if(!SchemaComparison.compare(model,physical).matches())
                        throw new IllegalArgumentException("目标物理表已存在且与自动生成结构不同，请换表名或先登记并接入已有 ODS 模型");
                    warnings.add("目标物理表已存在且结构一致，确认后只登记接入，不会覆盖或重建该表");
                }
            } catch(SQLException exception) { throw new IllegalArgumentException("无法核对 Doris 目标表，请检查连接及结构读取权限"); }
        }
        String previewHash=generated.isEmpty() || watermark.isEmpty()?"":OdsSchemaPolicy.hash(sourceHash+"\n"+watermark+"\n"+generated);
        return new Preview(model,List.copyOf(fields),schema.primaryKeys(),sourceHash,previewHash,generated,List.copyOf(warnings),
                !previewHash.isEmpty(),watermark,schema);
        } catch(IllegalArgumentException exception) {
            // Keep inspected fields available to repair physical settings, but never display a stale DDL or confirmation.
            throw new PreviewFailure(exception.getMessage(),new Preview(model,List.copyOf(fields),schema.primaryKeys(),sourceHash,"","",
                    List.copyOf(warnings),false,watermark,schema));
        }
    }

    @Transactional
    public long create(Input input,String expectedHash,boolean confirmed,String actor) {
        if(!confirmed) throw new IllegalArgumentException("请核对结构预览并勾选确认创建");
        if(input.name()==null || input.name().isBlank() || input.name().length()>100) throw new IllegalArgumentException("任务名称不能为空，最长100字符");
        SchedulePreview.validate(input.cronExpression());
        var plan=preview(input); // Always reread the source and regenerate the DDL; browser fields are not authoritative.
        if(!plan.ready() || !plan.previewHash().equals(expectedHash))
            throw new IllegalArgumentException("源结构或建表设置已变化，请重新读取结构并预览后确认");
        long modelId=modeling.save(null,plan.model(),actor);
        long pipelineId=pipelines.create(input.name(),input.sourceId(),input.sourceTable(),plan.watermarkColumn(),plan.model().getTableName(),"",input.cronExpression());
        platform.pipelineSchema(pipelineId,plan.sourceHash(),OdsSchemaPolicy.targetHash(plan.model()),String.join(",",plan.fields().stream()
                .filter(Mapping::keyColumn).map(Mapping::sourceName).toList()));
        // Complete metadata writes before the external DDL; database constraints fail before a table is created.
        var source=sources.require(input.sourceId());
        var sourceColumns=new ArrayList<JdbcMetadataCollector.Column>();
        for(int i=0;i<plan.sourceSchema().columns().size();i++) {
            var column=plan.sourceSchema().columns().get(i);
            sourceColumns.add(new JdbcMetadataCollector.Column(column.name(),OdsSchemaPolicy.sourceType(column),column.nullable(),
                    plan.primaryKeys().contains(column.name()),column.comment(),i+1));
        }
        metadata.registerTable(source.getId(),source.getNamespace(),new JdbcMetadataCollector.Table(input.sourceTable(),plan.sourceSchema().comment(),sourceColumns),actor);
        var targetColumns=new ArrayList<JdbcMetadataCollector.Column>();
        for(int i=0;i<plan.model().getFields().size();i++) {
            var field=plan.model().getFields().get(i);
            targetColumns.add(new JdbcMetadataCollector.Column(field.getName(),field.getDataType(),field.isNullable(),field.isKeyColumn(),field.getComment(),i+1));
        }
        metadata.registerTable(null,"warehouse",new JdbcMetadataCollector.Table(plan.model().getTableName(),plan.model().getDescription(),targetColumns),actor);
        try {
            var physical=gateway.inspect(plan.model().getTableName());
            var modelPreview=modeling.preview(modelId);
            if(physical.columns().isEmpty()) {
                var result=modeling.physicalize(modelId,modelPreview.model().getVersion(),modelPreview.hash(),actor);
                if(!result.success()) throw new IllegalArgumentException("ODS 创建未完成，管道未保存；请核对目标表后重新预览。"+result.message());
            } else if(!modeling.compare(modelId,modelPreview.model().getVersion(),actor).matches())
                throw new IllegalArgumentException("ODS 目标结构已变化，管道未保存，请重新预览");
        } catch(SQLException exception) { throw new IllegalArgumentException("ODS 创建未完成，管道未保存；请核对目标表后重试"); }
        platform.audit(actor,"ODS_PIPELINE_CREATE",Long.toString(pipelineId),"SUCCESS");
        return pipelineId;
    }
    private static String suggestedTarget(long source,String table) {
        String value="ods_"+source+"_"+table.toLowerCase(Locale.ROOT);
        return value.length()<=64?value:value.substring(0,55)+"_"+OdsSchemaPolicy.hash(value).substring(0,8);
    }
    private static String cleanComment(String value,int max) {
        String clean=Objects.toString(value,"").replaceAll("[\\p{Cntrl}\\\\]"," ").trim();
        return clean.substring(0,Math.min(max,clean.length()));
    }
}
