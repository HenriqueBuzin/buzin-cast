module.exports = {
  "dependency": {
    "platforms": {
      "kepler": {
        "autolink": {
          "com.buzincast.network": {
            "libraryName": "libBuzinNetwork.so",
            "provider": "application",
            "linkDynamic": true,
            "turbomodules": [
              "BuzinNetwork"
            ]
          }
        }
      }
    }
  }
};
