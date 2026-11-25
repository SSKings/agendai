# Redis oficial + correções de segurança do Alpine que a imagem ainda não trouxe (o scan do CI barra HIGH/CRITICAL que
# já têm correção — ex.: OpenSSL 3.5.9-r0 → r2, novembro de 2026). Mesmo padrão da imagem do front. Quando a imagem
# oficial trouxer a correção, o apk upgrade vira um passo vazio.
FROM redis:7.4-alpine@sha256:7f7d30b7fdd09467bac58198a1f166dd69789d348d4fe859cccb20e0cf287fbe
RUN apk upgrade --no-cache
