#pragma once

#include <memory>
#include <string>
#include <vector>

class MediaDownload {
public:
  struct Status {std::string phase; int percent;};
  struct Entry {std::string name; unsigned long long bytes;};

  explicit MediaDownload(std::string directory, std::string caFile = "/etc/ssl/certs/ca-certificates.crt");
  ~MediaDownload();
  void start(const std::string& url);
  void cancel();
  Status status() const;
  std::vector<Entry> list() const;
  std::string path(const std::string& name) const;
  void remove(const std::string& name);

private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
