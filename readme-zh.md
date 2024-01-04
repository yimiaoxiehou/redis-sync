### 本项目用于远程 redis 同步，数据源可以是 redis 或 AOF文件 或 RDB 文件

### 该项目基于 [redis-replicator](https://github.com/leonchen83/redis-replicator)

### 参数说明：
 
| arg            | require | example                                                                                     |
|----------------|---------|---------------------------------------------------------------------------------------------|
| source         | true    | 127.0.0.1:6379  （同步源，可以为 redis 地址，也可以为 aof/rdb 文件路径） |
| target         | true    | 127.0.0.1:6380                   |
| sourcePassword | false   | 同步源密码123456                                                                                      |
| targetPassword | false   | 目标密码123456                                                                                      |

### 用例

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