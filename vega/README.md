# Buzin Cast para Vega OS

Esta prévia abre a tela inicial com a marca do Buzin Cast. A primeira
entrega funcional será o espelhamento AirPlay com áudio.

O próximo desenvolvimento será feito no Mac, com o objetivo de alcançar os
recursos das duas edições Fire OS. O [ponto de retomada para o Codex no Mac](../docs/VEGA_HANDOFF.md)
registra o escopo, a ordem de implementação e as validações necessárias.
Ainda não há Fire TV Vega físico disponível; os primeiros testes usarão o simulador.

## Gerar e executar a prévia

Instale o [Vega Developer Tools](https://developer.amazon.com/docs/vega/latest/install-vega-sdk)
em macOS ou Ubuntu nativo. Na raiz deste repositório, use o template oficial
React Native 0.83 do SDK. A pasta gerada fica ao lado do repositório.

```sh
vega project generate --template helloWorld --name BuzinCast \
  --packageId com.buzincast.tv --outputDir ../buzin-cast-vega-preview
cp vega/App.tsx app/src/main/res/drawable-nodpi/buzin_banner.png ../buzin-cast-vega-preview/
cd ../buzin-cast-vega-preview
vega project update-manifest --os-min 2.0 --os-version 2.0
npm install
npm run build:app
```

Siga as [instruções de execução](https://developer.amazon.com/docs/vega/latest/run-apps)
para testar o VPKG. Para publicar na mesma listagem dos APKs Fire OS, mantenha
o ID `com.buzincast.tv` e confira no manifesto a categoria `com.amazon.category.main`.
Revise a versão mínima do sistema contra os modelos que devem receber o pacote.

## Primeiro teste do receptor

1. Em um Fire TV Vega físico, confirmar que um módulo nativo consegue aceitar
   conexões TCP e UDP da rede local e anunciar os serviços mDNS para um Mac
   ou iPhone. Repetir o teste com o pacote nas condições de distribuição.
2. Compilar o núcleo de protocolo do UxPlay para Vega e ligar a sessão RTSP
   ao módulo nativo. A ponte JNI, o shim DNS-SD e o pipeline Android de áudio
   e vídeo precisam de adaptadores para Vega.
3. Validar imagem e áudio simultâneos, desconexão e nova conexão pelo menu
   AirPlay. Depois validar os outros protocolos separadamente antes de
   anunciá-los na loja.
