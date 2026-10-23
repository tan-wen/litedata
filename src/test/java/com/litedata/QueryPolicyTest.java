package com.litedata;

import com.litedata.service.QueryPolicy;
import com.litedata.data.JdbcConnections;
import com.litedata.security.CredentialCipher;
import com.litedata.config.PlatformProperties;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class QueryPolicyTest {
    private final QueryPolicy policy=new QueryPolicy();
    @Test void valuesAreBoundAndQuotedColonIsNotAParameter() {
        var query=policy.validate("SELECT ':literal' AS note, id FROM warehouse.orders WHERE id=:id OR parent_id=:id");
        assertThat(query.parameters()).containsExactly("id","id");
        assertThat(query.sql()).isEqualTo("SELECT ':literal' AS note, id FROM warehouse.orders WHERE id=? OR parent_id=?");
        assertThat(query.mappedSql()).contains("#{values[0]}","#{values[1]}");
    }
    @Test void writesCrossDatabaseAndSideEffectsAreRejected() {
        for(String sql:new String[]{"DELETE FROM orders","SELECT * FROM platform.data_source",
                "SELECT * FROM orders; DELETE FROM orders","SELECT SLEEP(20) FROM orders",
                "SELECT * INTO OUTFILE '/tmp/out' FROM orders","SELECT * FROM orders FOR UPDATE",
                "SELECT * FROM mysql.user","SELECT LOAD_FILE('/etc/passwd') FROM orders"}) {
            assertThatThrownBy(()->policy.validate(sql)).as(sql).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void connectionHooksAndNonMysqlSourcesAreRejected() {
        var connections=new JdbcConnections();
        connections.validateUrl("jdbc:mysql://mysql:3306/demo?useSSL=false&serverTimezone=UTC");
        for(String url:new String[]{"jdbc:h2:mem:test","jdbc:mysql://mysql:3306/demo?socketFactory=evil",
                "jdbc:mysql://mysql:3306/demo?autoDeserialize=true","jdbc:mysql://mysql:3306/demo?allowLoadLocalInfile=true",
                "jdbc:mysql://user:secret@mysql:3306/demo"})
            assertThatThrownBy(()->connections.validateUrl(url)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void credentialsUseAuthenticatedRandomizedEncryption() {
        var properties=new PlatformProperties("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","test-password","internal-token",null,null);
        var cipher=new CredentialCipher(properties);
        String first=cipher.encrypt("never-store-plaintext"), second=cipher.encrypt("never-store-plaintext");
        assertThat(first).doesNotContain("never-store-plaintext").isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("never-store-plaintext");
        assertThatThrownBy(()->cipher.decrypt(first.substring(1))).isInstanceOf(IllegalStateException.class);
    }
}

