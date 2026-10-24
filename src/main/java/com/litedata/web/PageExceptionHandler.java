package com.litedata.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@ControllerAdvice(assignableTypes={PageController.class,UserController.class,ModelingController.class,TransformController.class,MetadataController.class,QualityController.class,MetricController.class,AssetCatalogController.class})
public class PageExceptionHandler {
    private final com.litedata.store.PlatformMapper mapper;
    public PageExceptionHandler(com.litedata.store.PlatformMapper mapper) { this.mapper=mapper; }
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,DataAccessException.class})
    String handle(Exception exception,HttpServletRequest request,RedirectAttributes redirect) {
        String segment=request.getRequestURI().split("/").length>1?request.getRequestURI().split("/")[1]:"";
        String target=Set.of("sources","pipelines","services","users","models","transforms","metadata","quality","metrics","assets").contains(segment)?"/"+segment:"/";
        if(request.getUserPrincipal()!=null && "POST".equals(request.getMethod()))
            mapper.audit(request.getUserPrincipal().getName(),"PAGE_MUTATION",request.getRequestURI(),"FAILED");
        redirect.addFlashAttribute("error",exception instanceof DataAccessException?"保存失败，请检查名称是否重复或配置是否有效":exception.getMessage());
        return "redirect:"+target;
    }
}

