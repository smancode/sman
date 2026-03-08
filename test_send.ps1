# test_send.ps1 - 发送消息到 SmanClaw Desktop
param(
    [string]$Message = "test",
    [string]$ConversationId = "036b0a3d-054e-4649-abff-3043b61a70d4",
    [string]$ProjectId = "7950de30-bd9d-4bd7-ab10-4f73eaff2c27"
)

# 防止 Windows 把 /xxx 当作路径处理，加上前缀空格
$body = @{
    content = " $($Message)"
    conversation_id = $ConversationId
    project_id = $ProjectId
} | ConvertTo-Json -Compress

try {
    $response = Invoke-RestMethod -Uri "http://127.0.0.1:18080/send" `
        -Method Post `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) `
        -ContentType "application/json; charset=utf-8"

    Write-Host "Send OK: $($response | ConvertTo-Json -Compress)"
} catch {
    Write-Host "Send Failed: $_"
}
