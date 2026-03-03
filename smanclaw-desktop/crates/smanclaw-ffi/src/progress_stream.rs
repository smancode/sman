//! Progress stream for real-time task updates

use smanclaw_types::ProgressEvent;
use tokio::sync::mpsc;

/// Progress stream receiver
pub type ProgressReceiver = mpsc::Receiver<ProgressEvent>;

/// Progress stream sender
pub type ProgressSender = mpsc::Sender<ProgressEvent>;

/// Create a progress channel
pub fn create_progress_channel(buffer_size: usize) -> (ProgressSender, ProgressReceiver) {
    mpsc::channel(buffer_size)
}

#[cfg(test)]
mod tests {
    use super::*;
    use smanclaw_types::FileAction;

    #[tokio::test]
    async fn progress_channel_works() {
        let (tx, mut rx) = create_progress_channel(10);

        tx.send(ProgressEvent::TaskStarted {
            task_id: "task-123".to_string(),
        })
        .await
        .expect("send");

        tx.send(ProgressEvent::Progress {
            message: "Working...".to_string(),
            percent: 0.5,
        })
        .await
        .expect("send");

        drop(tx);

        let mut events = vec![];
        while let Some(event) = rx.recv().await {
            events.push(event);
        }

        assert_eq!(events.len(), 2);
    }
}
