package com.litedata.store;

import com.litedata.model.DataService;
import com.litedata.security.UserApiKey;
import com.litedata.security.UserApiKeyService.ApiKeyView;
import com.litedata.security.UserApiKeyService.Identity;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UserApiKeyMapper {
    String VIEW="id,name,token_prefix,scopes,service_slugs,pipeline_ids,created_at,expires_at,last_used_at,revoked_at";
    String IDENTITY="SELECT k.id key_id,u.id user_id,u.username,u.role,k.scopes FROM user_api_key k JOIN app_user u ON u.id=k.user_id ";
    String ACTIVE=" AND k.revoked_at IS NULL AND k.expires_at>UTC_TIMESTAMP(6) AND u.enabled=TRUE";
    @Insert("INSERT INTO user_api_key(user_id,name,token_hash,token_prefix,scopes,service_slugs,pipeline_ids,expires_at) VALUES(#{userId},#{name},#{tokenHash},#{tokenPrefix},#{scopes},#{serviceSlugs},#{pipelineIds},#{expiresAt})")
    @Options(useGeneratedKeys=true,keyProperty="id") void insert(UserApiKey key);
    @Select("SELECT COUNT(*) FROM user_api_key WHERE user_id=#{userId}") long count(long userId);
    @Select("SELECT "+VIEW+" FROM user_api_key WHERE user_id=#{userId} ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<ApiKeyView> page(@Param("userId") long userId,@Param("limit") int limit,@Param("offset") long offset);
    @Select("SELECT "+VIEW+" FROM user_api_key WHERE id=#{id} AND user_id=#{userId}")
    ApiKeyView view(@Param("id") long id,@Param("userId") long userId);
    @Select(IDENTITY+"WHERE k.token_hash=#{hash}"+ACTIVE) Identity authenticate(String hash);
    @Select(IDENTITY+"WHERE k.id=#{keyId} AND k.user_id=#{userId}"+ACTIVE)
    Identity refresh(@Param("keyId") long keyId,@Param("userId") long userId);
    @Update("UPDATE user_api_key SET last_used_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND revoked_at IS NULL") void touch(long id);
    @Update("UPDATE user_api_key SET revoked_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND user_id=#{userId} AND revoked_at IS NULL")
    int revoke(@Param("id") long id,@Param("userId") long userId);
    @Insert("INSERT INTO user_api_key_service(key_id,service_id) VALUES(#{keyId},#{resourceId})")
    void grantService(@Param("keyId") long keyId,@Param("resourceId") long resourceId);
    @Insert("INSERT INTO user_api_key_pipeline(key_id,pipeline_id) VALUES(#{keyId},#{resourceId})")
    void grantPipeline(@Param("keyId") long keyId,@Param("resourceId") long resourceId);
    @Select("SELECT COUNT(*) FROM user_api_key_service WHERE key_id=#{keyId} AND service_id=#{resourceId}")
    int serviceGranted(@Param("keyId") long keyId,@Param("resourceId") long resourceId);
    @Select("SELECT COUNT(*) FROM user_api_key_pipeline WHERE key_id=#{keyId} AND pipeline_id=#{resourceId}")
    int pipelineGranted(@Param("keyId") long keyId,@Param("resourceId") long resourceId);
    @Select("SELECT s.* FROM data_service s JOIN user_api_key_service g ON g.service_id=s.id WHERE g.key_id=#{keyId} AND s.published=TRUE ORDER BY s.id LIMIT #{limit} OFFSET #{offset}")
    List<DataService> services(@Param("keyId") long keyId,@Param("limit") int limit,@Param("offset") long offset);
}
