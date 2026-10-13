FROM python:3.12-alpine
RUN apk add --no-cache docker-cli
WORKDIR /service
COPY server.py .
ENV PYTHONUNBUFFERED=1
EXPOSE 8090
CMD ["python", "server.py"]
