FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY index.html style.css app.js demo.js /usr/share/nginx/html/
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -q --spider http://localhost/ || exit 1
