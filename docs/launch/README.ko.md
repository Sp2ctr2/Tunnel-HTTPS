# 연결 버튼 아래에서: Android 로컬 네트워크 엔진 만들기

**Sp2ctr2 · Tunnel HTTPS**

Android에 VPN 표시가 뜬다고 해서 트래픽이 반드시 개발자의 VPN 서버로 향하는 것은 아닙니다. Tunnel HTTPS는 VpnService와 TUN을 로컬 패킷 처리 엔진의 입구로 사용합니다. 개발자가 운영하는 원격 전체 트래픽 VPN 게이트웨이는 없습니다.

[저장소](https://github.com/Sp2ctr2/Tunnel-HTTPS), [아키텍처](../release/01_ARCHITECTURE_AND_VPN_FLOW.md), [릴리스](https://github.com/Sp2ctr2/Tunnel-HTTPS/releases)를 공개하고 구체적인 기술 리뷰를 받고 있습니다. 설치 방법과 검증 파일은 비공식 미러가 아니라 해당 공식 릴리스에서 확인해 주세요.

## 무엇을 만들고 있는가

이 프로젝트의 질문은 단순합니다. 원격 전체 트래픽 게이트웨이를 운영하지 않고, Android 기기에서 트래픽이 외부로 나가기 전에 어느 정도의 네트워크 정책을 직접 처리할 수 있을까요?

Tunnel HTTPS는 패킷 정규화, DNS 검증과 캐시, 제한된 자원의 TCP·UDP 릴레이, TLS ClientHello 전략, 연결 결과에 따른 로컬 학습을 구현합니다. 화면의 연결 버튼보다 그 아래의 처리 경로에 집중한 프로젝트입니다.

일반적인 원격 VPN을 대체하지는 않습니다. 공인 IP나 국가를 바꾸지 않고, HTTPS 내용을 복호화하지 않으며, 익명성을 보장하지 않습니다. DNS 제공자와 접속 대상 서비스는 실제로 자신에게 전송되는 요청을 받습니다.

## 직접 구현한 영역과 플랫폼의 영역

Android는 VpnService와 TUN, 외부 연결에 사용하는 커널 소켓을 제공합니다. 이 저장소는 그 사이의 로컬 패킷 처리와 정책, 연결 상태를 담당합니다.

**TUN 쪽의 TCP 상태를 직접 다루는 것과 인터넷 전체에 대응하는 커널 TCP 스택을 재구현하는 것은 다릅니다.** 이 경계를 명확히 한 상태에서 코드를 보는 것이 중요합니다.

| 영역 | 저장소가 담당하는 부분 | 코드 |
| --- | --- | --- |
| 패킷 | IPv4·IPv6 검증, 정규화, 제한된 조각 처리 | [IPv6 정규화](../../app/src/main/java/com/tunnelvpn/app/Ipv6PacketNormalizer.kt) |
| DNS | 메시지 파싱, 응답 검증, 캐시 및 리졸버 정책 | [DNS 검증](../../app/src/main/java/com/tunnelvpn/app/DnsMessageValidator.kt) |
| 전송 | 로컬 TCP·UDP 릴레이 상태, 정리, 자원 상한 | [TCP 릴레이](../../app/src/main/java/com/tunnelvpn/app/TurboTcpForwarder.kt) |
| 핸드셰이크 | TLS ClientHello 파싱과 적용 가능한 분할 전략 | [ClientHello 파서](../../app/src/main/java/com/tunnelvpn/app/TlsClientHello.kt) |
| 적응 | 로컬 문맥 기반 전략 선택과 별도의 신경망 shadow 정책 | [Turbo](../../app/src/main/java/com/tunnelvpn/app/TurboAiEngine.kt) / [Aegis](../../app/src/main/java/com/tunnelvpn/app/AegisLocal2Engine.kt) |

이 설명은 구현 범위를 나타냅니다. 모든 프로토콜에 대한 완전한 적합성, 독립 보안 인증, 모든 네트워크에서의 성능 향상을 주장하는 것은 아닙니다.

## 실패 경로도 구현의 일부다

패킷 경로에 개입하는 애플리케이션에서는 잘못된 입력, 불완전한 조각, 오래된 연결 상태, 무제한 큐가 사용자 연결에 영향을 줄 수 있습니다.

따라서 [네트워크 보안·데이터 흐름 문서](../release/03_NETWORK_SECURITY_AND_DATA_FLOW.md)와 [테스트](../../app/src/test/java/com/tunnelvpn/app/)를 함께 봐 주세요. 구체적인 파서 입력, 상태 전이, 자원 제한을 재현 가능한 사례로 검토하는 피드백이 특히 유용합니다.

## 학습과 제어 권한은 구분한다

Turbo의 문맥 기반 학습기는 적용 가능한 연결 전략을 선택합니다. Aegis는 별도의 실험적 신경망 shadow 정책입니다. 신경망 코드가 있다는 이유만으로 모든 실시간 라우팅을 신경망이 제어한다고 설명하지 않습니다.

[Turbo 아키텍처](../TURBO_AI_ARCHITECTURE.md)와 현재 구현에서 선택, 관측, 안전 경계가 실제로 어디에 연결되는지 확인할 수 있습니다.

## 어떤 리뷰를 받고 싶은가

파서 문제를 드러내는 최소 합성 패킷, 재현 가능한 TCP 릴레이 회귀, DNS 검증과 외부 제공자 신뢰의 경계에 대한 지적, 기기 종류·Android 버전·빌드·재현 단계가 명확한 수명주기 문제가 좋은 출발점입니다.

JVM 테스트, 에뮬레이터 검사, 실기기 관찰, 통신사망 검증은 서로 구분합니다. 하나의 성공을 다른 검증의 성공으로 간주하지 않습니다. 날짜가 붙은 문서도 해당 시점의 기록이지 영구적인 보증이 아닙니다.

공개 베타를 시험할 때에는 먼저 중요한 작업이 없는 기기나 네트워크를 사용해 주세요. 기존 앱을 제거하면 로컬 데이터가 삭제될 수 있습니다. 일반 오류는 [Issues](https://github.com/Sp2ctr2/Tunnel-HTTPS/issues), 악용 가능한 취약점은 [보안 제보 절차](../../SECURITY.md)를 이용하고, 실제 방문 기록·개인 DNS 이름·인증정보는 공개 글에 첨부하지 마세요.

## 기능 목록보다 코드를 열어보세요

앱, 네트워크 구현, 테스트와 기술 문서를 Apache-2.0으로 공개합니다.

**[Tunnel HTTPS 살펴보기](https://github.com/Sp2ctr2/Tunnel-HTTPS)** · **[기여 안내](../../CONTRIBUTING.md)** · **[English](README.md)**

유용하거나 지켜볼 만한 프로젝트라면 Star로 따라올 수 있습니다. 재현 사례, 세밀한 코드 리뷰와 정직한 호환성 보고는 더 큰 도움이 됩니다. 허위 성능 주장, 구매한 참여, 투표 동원 방식의 홍보는 하지 않습니다.
