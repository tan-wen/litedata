package com.litedata.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class BreadcrumbConfiguration implements WebMvcConfigurer {
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public void postHandle(HttpServletRequest request,HttpServletResponse response,Object handler,ModelAndView view) {
                if(view==null || view.getViewName()==null || view.getViewName().startsWith("redirect:") || view.getViewName().startsWith("forward:")) return;
                view.addObject("breadcrumbs",PageBreadcrumbs.forView(view.getViewName(),view.getModel()));
            }
        });
    }
}
