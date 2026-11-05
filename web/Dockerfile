FROM node:20-alpine
WORKDIR /app
COPY server.js .
COPY public ./public
USER node
EXPOSE 8080 8443
CMD ["node", "server.js"]
