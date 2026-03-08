# test_send.ps1 - 发送消息到 SmanClaw Desktop
param(
    [string]$Message = "test"
)

$body = @{
    content = $Message
    conversation_id = "test"
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
