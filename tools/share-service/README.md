# Сервис приёма архивов «Поделиться данными»

Маленький сервис выдаёт игре одноразовую ссылку на загрузку архива в приватный S3-совместимый бакет. Архив
идёт из игры прямо в хранилище, через сервис он не проходит. Нужен только Python 3.8+, сторонних пакетов нет:
подпись AWS SigV4 сделана вручную (`sigv4.py`) и сверена с примерами из документации AWS.

```
игра ── POST /v1/upload {install_id, size, sha256, manifest_summary} ──▶ сервис (квоты, sqlite)
     ◀── {upload_id, url (presigned PUT, 15 мин), headers} ─────────────
игра ── PUT url (тело — zip, Content-Length подписан) ─────────────────▶ бакет: incoming/<yyyy-mm-dd>/<install_id>/<uuid>.zip
игра ── POST /v1/complete {upload_id, install_id} ──▶ сервис ── HEAD объекта (длина, контрольная сумма) ──▶ бакет
```

## API

| Запрос | Ответ |
|---|---|
| `POST /v1/upload` `{"install_id": "<32 hex>", "size": <байт>, "sha256": "<64 hex>", "manifest_summary": {…≤4 КБ}}` | `200 {"upload_id", "url", "method": "PUT", "headers": {"Content-Length": …[, "x-amz-checksum-sha256": …]}, "expires_in": 900}` |
| | `400 bad_request` (поле и что не так), `413 too_large` (`max_size`), `429 quota` (`reason`: `install_daily`, `ip_daily`, `total_daily`, `total_bytes`; `retry_after` — секунд до конца суток UTC, он же в заголовке `Retry-After`), `503 not_configured` |
| `POST /v1/complete` `{"upload_id", "install_id"}` | `200 {"status": "completed"}`; `404 unknown`; `409 not_uploaded` / `size_mismatch` / `checksum_mismatch` |
| `GET /healthz` | `200 {"ok": true, "configured": …}` |

- **Ссылка**: presigned PUT по SigV4, живёт `S3_URL_TTL` секунд (по умолчанию 900). `Content-Length` входит в
  подписанные заголовки, поэтому хранилище не примет тело другой длины. С `SHARE_SIGN_CHECKSUM=1` в подпись
  входит и `x-amz-checksum-sha256` (base64 от SHA-256 архива). Тогда хранилище само сверяет содержимое, если умеет
  проверять контрольные суммы (у AWS S3 это есть; умеет ли Timeweb — не проверено, по умолчанию выключено).
- **Проверка после загрузки** (`/v1/complete`): HEAD объекта, сверка длины и, если хранилище её вернуло, суммы.
  С `SHARE_VERIFY_DOWNLOAD=1` сервис скачивает объект и считает SHA-256 сам: так надёжнее, но каждый архив
  проходит через сервер ещё раз.
- **Квоты** на сутки UTC (sqlite, таблица `uploads`):
  - архивов на install_id;
  - архивов на IP клиента;
  - архивов всего;
  - байт всего;
  - предельный размер одного архива.

  Ссылка засчитывается в квоту в момент выдачи, даже если загрузки так и не было: иначе квоты можно обойти,
  запрашивая ссылки без конца. IP хранится только как HMAC-SHA256 с солью `SHARE_IP_SALT`, сам адрес в базу и в
  лог сервиса не пишется.
- `install_id` — 32 случайных hex-знака, их игра один раз создаёт в `config/aiguide/share.json`. С ником он не
  связан.

## Переменные окружения

Полный список — в `share.env.example`, значений в коде нет.

| Переменная | Что | По умолчанию |
|---|---|---|
| `S3_ENDPOINT` | адрес S3 API хранилища, `https://…` | — (обязательно) |
| `S3_REGION` | регион (у Timeweb, по словам владельца, `ru-1` — сверить в панели) | — (обязательно) |
| `S3_BUCKET` | имя бакета | — (обязательно) |
| `S3_ACCESS_KEY_ID`, `S3_SECRET_ACCESS_KEY` | ключ доступа к хранилищу | — (обязательно) |
| `S3_PATH_STYLE` | `1` — `https://endpoint/bucket/key`, `0` — `https://bucket.endpoint/key` | `1` |
| `S3_URL_TTL`, `S3_PREFIX` | срок ссылки (с), префикс ключей | `900`, `incoming` |
| `SHARE_SIGN_CHECKSUM`, `SHARE_VERIFY_DOWNLOAD` | см. выше | `0`, `0` |
| `SHARE_IP_SALT` | длинная случайная строка (`openssl rand -hex 32`). Без неё соль случайная на каждый запуск, и квоты по IP сбрасываются при рестарте | — |
| `SHARE_TRUST_PROXY` | `1` — брать IP из `X-Real-IP` (только за nginx!) | `0` (в Dockerfile `1`) |
| `SHARE_DB`, `SHARE_BIND`, `SHARE_PORT` | база, адрес, порт | `share.sqlite`, `127.0.0.1`, `8080` |
| `SHARE_MAX_SIZE` | макс. размер архива, байт | 200 МиБ |
| `SHARE_PER_INSTALL_DAY`, `SHARE_PER_IP_DAY` | архивов в сутки на установку / на IP | `3`, `10` |
| `SHARE_DAY_COUNT`, `SHARE_DAY_BYTES` | всего архивов / байт в сутки | `500`, 20 ГиБ |

## Развёртывание

Ниже — порядок действий. Ничего из этого пока не сделано и на настоящем хранилище не проверялось.

1. **В аккаунте хранилища** (Timeweb Cloud → S3):
   - создать **приватный** бакет, например `aiguide-share`: без публичного чтения и без листинга;
   - получить ключ доступа (Access Key / Secret Key). Если хранилище позволяет, ограничить ключ этим бакетом;
   - записать адрес S3 API и регион из панели (они пойдут в `S3_ENDPOINT` и `S3_REGION`);
   - CORS не нужен: загружает игра, а не браузер.
2. **Политика жизненного цикла** для `incoming/`: `lifecycle.json` удаляет объекты через 30 дней и обрывает
   незавершённые multipart-загрузки. Ставится, например, так:
   `aws s3api put-bucket-lifecycle-configuration --endpoint-url <S3_ENDPOINT> --bucket <бакет> --lifecycle-configuration file://lifecycle.json`.
   Поддерживает ли хранилище lifecycle через API, не проверено; если нет, правило задаётся в панели. Срок выбрать
   под то, как быстро архивы забираются в обучение (переносятся из `incoming/` в другое место).
3. **Секреты**: `share.env` по образцу `share.env.example` хранить в `C:\develop\ops` (SOPS), не в git.
4. **Сервер**:
   - `docker build -t aiguide-share tools/share-service`
   - `docker run -d --name aiguide-share --restart unless-stopped --env-file share.env -p 127.0.0.1:8080:8080 -v aiguide-share-data:/data aiguide-share`
5. **nginx**: `nginx.conf.example` — https-vhost с лимитом запросов и `X-Real-IP`. Имя домена и сертификат свои.
6. **Проверка**: `curl https://<домен>/healthz` → `{"ok": true, "configured": true}`.
7. **Игра**: в `config/aiguide/share.json` вписать `"shareService": "https://<домен>"`. `installId` игра допишет
   сама. После этого на экране «Поделиться данными» появится кнопка «Отправить…».

Где лежат загрузки: в бакете по ключам `incoming/<дата>/<install_id>/<uuid>.zip`, учёт — в `/data/share.sqlite`
(`status`: `issued` → `completed`, `manifest_summary` — id архива, число файлов, отмеченные пункты).

## Тесты (без сети, всё на 127.0.0.1)

```
python tools/share-service/tests/test_sigv4.py     # подпись по примерам AWS (signing key, presigned GET, GET с Range) и обратная проверка
python tools/share-service/tests/test_service.py   # валидация, квоты, ссылки, complete против фальшивого S3, HTTP-слой, X-Real-IP
```

`test_service.py` также гоняет настоящую отправку мода (`ShareUpload` через `ShareProbe`) против сервиса и
фальшивого S3, если `tools/test_client.py` уже скомпилировал тестовые классы. `tools/test_client.py` запускает
оба файла сам.
