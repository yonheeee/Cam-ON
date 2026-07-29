import { useEffect } from 'react';
import { useCharadesMicrophone } from '../hooks/useCharadesMicrophone';

interface CharadesMicrophoneControllerProps {
  roomId: string;
  accessToken: string;
  participantId: string;
  onPresenterChange: (isPresenter: boolean) => void;
}

export function CharadesMicrophoneController({
  roomId,
  accessToken,
  participantId,
  onPresenterChange,
}: CharadesMicrophoneControllerProps) {
  const { isPresenter, microphoneMutedByRole, microphoneError } = useCharadesMicrophone(
    roomId,
    accessToken,
    participantId,
  );

  useEffect(() => {
    onPresenterChange(isPresenter);
  }, [isPresenter, onPresenterChange]);

  if (!isPresenter) {
    return null;
  }

  return (
    <div className="video-call-room__charades-microphone" role="status" aria-live="polite">
      {microphoneError
        ? `표현자 마이크 음소거 실패: ${microphoneError}`
        : microphoneMutedByRole
          ? '표현 중에는 마이크가 자동으로 음소거됩니다.'
          : '표현자 마이크를 음소거하고 있습니다.'}
    </div>
  );
}
