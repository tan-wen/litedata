package com.litedata.service;

import com.litedata.config.PlatformProperties;
import com.litedata.data.JdbcConnections;
import java.util.*;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.stereotype.Component;

@Component
public class MyBatisRuntimeQueries {
    private final SqlSessionFactory factory;
    public MyBatisRuntimeQueries(PlatformProperties properties,JdbcConnections connections) {
        var doris=properties.doris(); connections.validateUrl(doris.jdbcUrl());
        var dataSource=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",doris.jdbcUrl(),doris.username(),doris.password());
        Properties flags=new Properties();
        flags.setProperty("connectTimeout","5000"); flags.setProperty("socketTimeout","20000");
        flags.setProperty("allowMultiQueries","false"); flags.setProperty("allowLoadLocalInfile","false"); flags.setProperty("autoDeserialize","false");
        dataSource.setDriverProperties(flags);
        var configuration=new Configuration(new Environment("doris",new JdbcTransactionFactory(),dataSource));
        configuration.setDefaultStatementTimeout(15); configuration.setCallSettersOnNulls(true);
        configuration.addMapper(RuntimeQueryMapper.class);
        factory=new SqlSessionFactoryBuilder().build(configuration);
    }
    public List<Map<String,Object>> execute(QueryPolicy.BoundQuery query,List<String> values,int limit,long offset) {
        try(var session=factory.openSession()) {
            session.getConnection().setReadOnly(true);
            return session.getMapper(RuntimeQueryMapper.class).query(query.mappedSql(),values,limit,offset);
        } catch(java.sql.SQLException exception) { throw new IllegalStateException("无法连接只读查询库"); }
    }
}

