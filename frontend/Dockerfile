FROM nginx:alpine

# 1. 清理
RUN rm -rf /usr/share/nginx/html/*

# 2. 拷贝 (既然 context 已经是 frontend 目录了，这里直接写目录名)
COPY dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]