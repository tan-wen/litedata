package com.litedata.data;

import com.litedata.model.DataSource;
import com.litedata.security.CredentialCipher;
import com.litedata.store.PlatformMapper;
import java.sql.*;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SourceService {
    private final PlatformMapper mapper; private final CredentialCipher cipher; private final JdbcConnections connections;
    public SourceService(PlatformMapper mapper, CredentialCipher cipher, JdbcConnections connections) {
        this.mapper = mapper; this.cipher = cipher; this.connections = connections;
    }
    public List<DataSource> list() { return mapper.sources(); }
    public DataSource require(long id) {
        DataSource source = mapper.source(id);
        if (source == null) throw new IllegalArgumentException("数据源不存在");
        return source;
    }
    public void create(String name, String url, String user, String password) {
        mapper.insertSource(definition(name, url, user, password));
    }
    public void update(long id, String name, String url, String user, String password) {
        var existing = require(id);
        if (!existing.getUsername().equals(user) && existing.getDatabaseType()!=DatabaseType.MYSQL &&
                (mapper.pipelines().stream().anyMatch(p -> p.getSourceId()==id) || mapper.qualitySourceReferences(id)>0))
            throw new IllegalArgumentException("数据源已被任务引用，修改数据库用户名请新建数据源，避免改变默认模式和水位口径");
        if (!existing.getJdbcUrl().equals(url) && mapper.pipelines().stream().anyMatch(p -> p.getSourceId() == id))
            throw new IllegalArgumentException("数据源已被同步管道引用，修改连接地址请新建数据源和管道");
        if (!existing.getJdbcUrl().equals(url) && mapper.qualitySourceReferences(id)>0)
            throw new IllegalArgumentException("数据源已被质量任务引用，修改连接地址请新建数据源及质量任务，以保留历史对账口径");
        var source = definition(name, url, user, password);
        source.setId(id);
        if (password == null || password.isEmpty()) source.setEncryptedPassword(existing.getEncryptedPassword());
        mapper.updateSource(source);
    }
    private DataSource definition(String name, String url, String user, String password) {
        if (name == null || name.isBlank() || name.length() > 100 || user == null || user.isBlank() || user.length() > 100)
            throw new IllegalArgumentException("请输入有效的数据源名称与用户名");
        connections.validateUrl(url);
        DataSource source = new DataSource(); source.setName(name.trim()); source.setJdbcUrl(url);
        source.setUsername(user); source.setEncryptedPassword(cipher.encrypt(password == null ? "" : password));
        return source;
    }
    public void test(long id) {
        var source = require(id);
        try (Connection connection = connect(source); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(5); statement.executeQuery(source.getDatabaseType().getTestSql()).close();
            mapper.sourceStatus(id, "CONNECTED", null);
        } catch (SQLException e) {
            mapper.sourceStatus(id, "FAILED", "连接失败，SQLState=" + e.getSQLState());
            throw new IllegalArgumentException("数据源连接失败，请检查地址、账号和网络");
        }
    }
    public Connection connect(DataSource source) throws SQLException {
        return connections.open(source.getJdbcUrl(), source.getUsername(), cipher.decrypt(source.getEncryptedPassword()));
    }
    public String password(DataSource source) { return cipher.decrypt(source.getEncryptedPassword()); }
}

