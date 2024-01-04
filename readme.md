### this project is for sync redis by remote redis or redis AOF/RDB file

### this project power on [redis-replicator](https://github.com/leonchen83/redis-replicator)

### args：
 
| arg            | require | example                                                                                     |
|----------------|---------|---------------------------------------------------------------------------------------------|
| source         | true    | 127.0.0.1:6379  （source can be a redis address or redis AOF filepath or redis RDB filepath） |
| target         | true    | 127.0.0.1:6380                                       |
| sourcePassword | false   | 123456                                                                                      |
| targetPassword | false   | 123456                                                                                      |

### usage:

1. Redis to Redis
    ```
    docker run -d -e source=192.168.44.188:6379 -e sourcePassword=123456 -e target=192.168.44.188:6380 -e targetPassword=123456 yimiaoxiehou/redis-sync:latest 
    ```
2. Aof to Redis
    ```
    docker run -d -v ./appendonly.aof:/appendonly.aof -e source=/appendonly.aof -e target=192.168.44.188:6380 -e targetPassword=123456 yimiaoxiehou/redis-sync:latest 
    ```
3. Rdb to Redis
    ```
    docker run -d -v ./dump.rdb:/dump.rdb -e source=/appendonly.aof -e target=192.168.44.188:6380 -e targetPassword=123456 yimiaoxiehou/redis-sync:latest 
    ```