# Compressing Redis Cache Library

## 개요

`CompressingCacheManagerPostProcessor`는 이 라이브러리의 핵심 컴포넌트 중 하나로, 압축 대상 `RedisCacheManager`를 래핑하여
`CompressingRedisCacheManager`를 생성합니다.

## 작동 방식

- **`CompressingRedisCacheManager`**  
  `CompressingRedisCacheManager`는 캐시를 반환할 때 `CompressingRedisCacheWrapper`를 제공합니다.  
  Wrapper는 기본 `RedisCache`와 유사한 방식으로 작동하지만, 마치 프록시가 동작하는 것 처럼 값을 캐시에 쓰고 읽을 때 직렬화 및 역직렬화 과정에 데이터를 **압축 및 압축 해제**하는 과정이 추가됩니다.
  이로써 적재되는 실제 값의 크기가 줄어드는 것을 확인할 수 있으며, 나머지 기능은 기존 RedisCache와 동일하게 동작합니다.

## 주요 기능

### 1. 기존 소스 코드 변경 없이 통합 가능
- 이 라이브러리는 기존 프로젝트와 쉽게 통합될 수 있도록 설계되었습니다.
- 단, `application.yml` 파일에 아래와 같은 설정만 추가하면 됩니다:
  - **래핑할 대상 `CacheManager`의 이름**
  - **압축을 적용할 임계값** (byte 단위)

### 2. 기본 설정 제공
- 애플리케이션에 `CacheManager`가 명시적으로 설정되지 않은 경우, 라이브러리가 **기본값**을 제공하여 동작하도록 설계되었습니다. 이 때 레디스의 주소는 127.0.0.1:6379로 동작합니다.
- CacheDefaultConfiguration를 참고

## 설정 예제

아래는 `application.yml`에 추가할 수 있는 설정 예제입니다:

```yaml
cache:
  compressing:
    targetCacheManager: redisCacheManager # 래핑할 CacheManager의 이름
    thresholdSize: 1024 # 압축을 적용할 값의 최소 크기 (byte 단위)
