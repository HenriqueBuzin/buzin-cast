#pragma once
#include <string>

// The path is supplied by the caller so storage can be verified off-device.
std::string readReceiverName(const std::string& path);
void writeReceiverName(const std::string& path, const std::string& name);
void validateReceiverName(const std::string& name);
bool readApprovalSetting(const std::string& path);
void writeApprovalSetting(const std::string& path, bool enabled);
