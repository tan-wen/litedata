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
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView view) {
                if (view == null || view.getViewName() == null || view.getViewName().startsWith("redirect:") || view.getViewName().startsWith("forward:"))
                    return;
                var breadcrumbs = PageBreadcrumbs.forView(view.getViewName(), view.getModel());
                view.addObject("breadcrumbs", breadcrumbs);
                view.addObject("pageBack", PageBreadcrumbs.backForView(view.getViewName(), view.getModel()));
                view.addObject("shellUsername", request.getUserPrincipal() == null ? "用户" : request.getUserPrincipal().getName());
                view.addObject("shellRole", request.isUserInRole("ADMIN") ? "管理员" : request.isUserInRole("DEVELOPER") ? "开发者" : "只读用户");
            }
        });
    }
}
