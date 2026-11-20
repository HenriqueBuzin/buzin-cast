#pragma once
#include <string>

// The path is supplied by the caller so storage can be verified off-device.
std::string readReceiverName(const std::string& path);
void writeReceiverName(const std::string& path, const std::string& name);
void validateReceiverName(const std::string& name);
std::string readExperienceProfile(const std::string& path);
void writeExperienceProfile(const std::string& path, const std::string& profile);
int readVideoMargin(const std::string& path);
void writeVideoMargin(const std::string& path, double percent);
bool readApprovalSetting(const std::string& path);
void writeApprovalSetting(const std::string& path, bool enabled);
