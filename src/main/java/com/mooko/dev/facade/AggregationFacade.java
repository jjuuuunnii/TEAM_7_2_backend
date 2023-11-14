package com.mooko.dev.facade;

import com.mooko.dev.configuration.S3Config;
import com.mooko.dev.domain.*;
import com.mooko.dev.dto.day.req.BarcodeDateDto;
import com.mooko.dev.dto.day.res.CalendarDto;
import com.mooko.dev.dto.day.res.DayDto;
import com.mooko.dev.dto.day.res.ThumbnailDto;
import com.mooko.dev.dto.event.req.NewEventDto;
import com.mooko.dev.dto.event.req.UpdateEventDateDto;
import com.mooko.dev.dto.event.req.UpdateEventNameDto;
import com.mooko.dev.dto.event.res.EventInfoDto;
import com.mooko.dev.dto.event.res.UserInfoDto;
import com.mooko.dev.dto.event.socket.UserEventCheckStatusDto;
import com.mooko.dev.dto.user.res.UserEventStatusDto;
import com.mooko.dev.event.ButtonEvent;
import com.mooko.dev.exception.custom.CustomException;
import com.mooko.dev.exception.custom.ErrorCode;
import com.mooko.dev.service.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AggregationFacade {
    private final EventService eventService;
    private final UserService userService;
    private final EventPhotoService eventPhotoService;
    private final BarcodeService barcodeService;
    private final UserBarcodeService userBarcodeService;
    private final S3Service s3Service;
    private final S3Config s3Config;
    private final ApplicationEventPublisher eventPublisher;
    private final DayService dayService;
    private final DayPhotoService dayPhotoService;


    /**
     * EventController
     */

    //makeNewEvent
    public void makeNewEvent(User tempUser, NewEventDto newEventDto) {
        User user = userService.findUser(tempUser.getId());
        checkUserEventStatus(user);
        eventService.makeNewEvent(newEventDto, user);
    }

    private void checkUserEventStatus(User user) {
        Optional.ofNullable(user.getEvent())
                .ifPresent(e -> {
                    throw new CustomException(ErrorCode.USER_ALREADY_HAS_EVENT);
                });
    }


    //ShowEventPage
    public EventInfoDto showEventPage(User tmpUser, Long eventId) {
        User user = userService.findUser(tmpUser.getId());
        Event event = eventService.findEvent(eventId);
        if(event.getUsers().stream().noneMatch(existingUser -> existingUser.equals(user))){
            eventService.addUser(user, event);
            userService.addEvent(user, event);
        }

        List<String> profilImgeUrlList = event.getUsers().stream().map(User::getProfileUrl).toList();
        boolean isRoomMaker = user.equals(event.getRoomMaker());

        List<UserInfoDto> userInfoList = event.getUsers().stream()
                .map(eventUser -> {
                    List<EventPhoto> eventPhotoList = eventPhotoService.findUserEventPhotoList(eventUser, event);
                    List<String> evnetPhotoUrlList = eventPhotoList.stream().map(EventPhoto::getUrl).toList();


                    if (evnetPhotoUrlList.isEmpty()) {
                        return null;
                    }

                    return UserInfoDto.builder()
                            .userId(eventUser.getId().toString())
                            .nickname(eventUser.getNickname())
                            .imageUrlList(evnetPhotoUrlList)
                            .checkStatus(eventUser.getCheckStatus())
                            .imageCount(evnetPhotoUrlList.size())
                            .build();
                })
                .toList();

        return EventInfoDto.builder()
                .profileImgUrlList(profilImgeUrlList)
                .isRoomMaker(isRoomMaker)
                .eventName(event.getTitle())
                .startDate(event.getStartDate())
                .endDate(event.getEndDate())
                .userInfo(userInfoList)
                .build();
    }


    //updateEventName
    public void updateEventName(User tmpUser, UpdateEventNameDto updateEventNameDto, Long eventId) {
        User user = userService.findUser(tmpUser.getId());
        Event event = eventService.findEvent(eventId);
        checkUserRoomMaker(user, event);
        eventService.updateEventName(updateEventNameDto.getEventName(), event);
    }


    //updateEventDate
    public void updateEventDate(User tmpUser, UpdateEventDateDto updateEventDateDto, Long eventId) {
        User user = userService.findUser(tmpUser.getId());
        Event event = eventService.findEvent(eventId);
        checkUserRoomMaker(user, event);
        eventService.updateEventDate(updateEventDateDto, event);
    }

    //makeNewBarcode
    public Long makeNewBarcode(User tmpUser, Long eventId) throws IOException {
        User user = userService.findUser(tmpUser.getId());
        Event event = eventService.findEvent(eventId);
        checkUserRoomMaker(user, event);
        List<String> eventPhotoList = eventPhotoService.findAllEventPhotoList(event);

        String barcodeFileName = s3Service.makefileName();
        String barcodeFilePath = s3Config.getBarcodeDir() + barcodeFileName;

        File barcodeFile = barcodeService.makeNewBarcode(eventPhotoList, barcodeFilePath);
        s3Service.putFileToS3(barcodeFile, barcodeFileName, s3Config.getBarcodeDir());

        Barcode barcode = barcodeService.saveBarcode(
                barcodeFilePath,
                event.getTitle(),
                event.getStartDate(),
                event.getEndDate(),
                BarcodeType.EVENT,
                event);
        userBarcodeService.makeUserBarcode(event.getUsers(), barcode);
        eventService.updateEventStatus(event, false);
        return barcode.getId();
    }

    private void checkUserRoomMaker(User user, Event event) {
        if (!event.getRoomMaker().equals(user)) {
            throw new CustomException(ErrorCode.NOT_ROOM_MAKER);
        }
    }


    //showUserEventStatus
    public UserEventStatusDto showUserEventStatus(User tmpUser) {
        User user = userService.findUser(tmpUser.getId());
        if (user.getEvent() != null) {
            return UserEventStatusDto.builder()
                    .isExistEvent(true)
                    .eventId(user.getEvent().getId().toString())
                    .build();
        }

        return UserEventStatusDto.builder()
                .isExistEvent(false)
                .eventId(null)
                .build();
    }


    //updateUserEventPhoto
    public void updateUserEventPhoto(User tmpUser, Long eventId, List<File> newPhotoList) {
        User user = userService.findUser(tmpUser.getId());
        Event event = eventService.findEvent(eventId);
        if (!event.getActiveStatus()) {
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }

        List<String> newPhotoUrlList = newPhotoList.parallelStream()
                .map(newPhoto -> {
                    String fileName = s3Service.makefileName();
                    return s3Service.putFileToS3(newPhoto, fileName, s3Config.getEventImageDir());
                }).collect(Collectors.toList());

        List<EventPhoto> eventPhotoList = eventPhotoService.findUserEventPhotoList(user, event);
        if (!eventPhotoList.isEmpty()) {
            deleteExistingPhotos(eventPhotoList);
        }
        eventPhotoService.makeNewEventPhoto(user, event, newPhotoUrlList);
    }



    //deleteUserEventPhoto
    public void deleteUserEventPhoto(User tmpUser, Long eventId) {
        User user = userService.findUser(tmpUser.getId());
        Event event = eventService.findEvent(eventId);
        if (!event.getActiveStatus()) {
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }
        List<EventPhoto> eventPhotoList = eventPhotoService.findUserEventPhotoList(user, event);
        if (!eventPhotoList.isEmpty()) {
            deleteExistingPhotos(eventPhotoList);
        }
    }

    private void deleteExistingPhotos(List<EventPhoto> eventPhotoList) {
        eventPhotoList.forEach(eventPhoto -> {
            s3Service.deleteFromS3(eventPhoto.getUrl());
        });
        eventPhotoService.deleteEventPhoto(eventPhotoList);
    }


    /**
     *
     * SocketController
     */

    //updateUserEventCheckStatus
    public UserEventCheckStatusDto updateUserEventCheckStatus(UserEventCheckStatusDto userEventCheckStatusDto, Long eventId) {
        User user = userService.findUser(Long.parseLong(userEventCheckStatusDto.getUserId()));
        userService.updateCheckStatus(user, userEventCheckStatusDto.isCheckStatus());
        Event event = eventService.findEvent(eventId);
        checkEventButtonStatus(event);
        return UserEventCheckStatusDto.builder()
                .checkStatus(user.getCheckStatus())
                .userId(user.getId().toString())
                .build();
    }

    //버튼 이벤트처리
    private void checkEventButtonStatus(Event event) {
        boolean allUsersChecked = event.getUsers().stream()
                .allMatch(User::getCheckStatus);

        eventPublisher.publishEvent(
                ButtonEvent.builder()
                        .buttonStatus(allUsersChecked)
                        .eventId(event.getId().toString())
                        .build());
    }

    /**
     *
     * DayController
     */

    // showUserCalendar
    public CalendarDto showCalendar(User tmpUser, String startDate, String endDate){
        User user = userService.findUser(tmpUser.getId());

        LocalDate startLocalDate = LocalDate.parse(startDate);
        LocalDate endLocalDate = LocalDate.parse(endDate);

        List<ThumbnailDto> thumbnailInfoList = new ArrayList<>();

        Boolean buttonStatus = true;

        while (!startLocalDate.isAfter(endLocalDate)) {
            startLocalDate = startLocalDate.plusDays(1);

            int year = startLocalDate.getYear();
            int month = startLocalDate.getMonthValue();
            int day = startLocalDate.getDayOfMonth();

            Day currentDay = dayService.findDayId(user,year,month,day);

            DayPhoto dayPhoto = dayPhotoService.findThumnail(currentDay);
            if (dayPhoto.getUrl()==null){
                buttonStatus=false;
            }

            ThumbnailDto thumbnailDto = ThumbnailDto.builder()
                    .thumbnailUrl(dayPhoto.getUrl())
                    .date(startLocalDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                    .build();
            thumbnailInfoList.add(thumbnailDto);
        }

        return CalendarDto.builder()
                .thumbnailInfoList(thumbnailInfoList)
                .buttonStatus(buttonStatus)
                .build();
    }

    // showDayPost
    public DayDto showDay(User tmpUser, String date){
        User user = userService.findUser(tmpUser.getId());

        LocalDate currentDate = LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        int year = currentDate.getYear();
        int month = currentDate.getMonthValue();
        int day = currentDate.getDayOfMonth();

        Day currentDay = dayService.findDayId(user,year,month,day);

        if (currentDay.getId()==null){
            currentDay = dayService.makeDay(user,year,month,day);
        }

        String memo = dayService.findMemo(currentDay);
        List<DayPhoto> dayImageList = dayPhotoService.findDayPhotoList(currentDay);

        List<String> dayPhotoUrlList = dayImageList.stream().map(DayPhoto::getUrl).toList();

        return DayDto.builder()
                .dayImageList(dayPhotoUrlList)
                .memo(memo)
                .build();
    }

    // post,updateDayPost
    public void updateDay(User tmpUser, String date, String memo, File thumbnail, List<File> newDayPhotoList){
        User user = userService.findUser(tmpUser.getId());

        LocalDate currentDate = LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        int year = currentDate.getYear();
        int month = currentDate.getMonthValue();
        int day = currentDate.getDayOfMonth();

        Day currentDay = dayService.findDayId(user,year,month,day);

        dayService.updateMemo(currentDay, memo);

        // Thumbnail
        String newThumbnailUrl = null;
        if (thumbnail!=null){
            String fileName = s3Service.makefileName();
            newThumbnailUrl = s3Service.putFileToS3(thumbnail, fileName, s3Config.getEventImageDir());
        }
        DayPhoto dayThumbnail = dayPhotoService.findThumnail(currentDay);
        if (dayThumbnail.getUrl()!=null) {
            s3Service.deleteFromS3(dayThumbnail.getUrl());
            dayPhotoService.deleteThumbnail(dayThumbnail);
        }
        dayPhotoService.makeNewThumbnail(currentDay,newThumbnailUrl, true);

        // Photos except thumbnail
        List<String> newDayPhotoUrlList = new ArrayList<>();;
        if (newDayPhotoList!=null){
            newDayPhotoUrlList = newDayPhotoList.parallelStream()
                    .map(newPhoto -> {
                        String fileName = s3Service.makefileName();
                        return s3Service.putFileToS3(newPhoto, fileName, s3Config.getEventImageDir());
                    }).collect(Collectors.toList());
        }

        List<DayPhoto> dayPhotoList = dayPhotoService.findDayPhotoList(currentDay);
        if (!dayPhotoList.isEmpty()) {
            deleteExistingDayPhotos(dayPhotoList);
        }
        dayPhotoService.makeNewDayPhoto(currentDay,newDayPhotoUrlList, false);
    }

    private void deleteExistingDayPhotos(List<DayPhoto> dayPhotoList) {
        dayPhotoList.forEach(eventPhoto -> {
            s3Service.deleteFromS3(eventPhoto.getUrl());
        });
        dayPhotoService.deleteDayPhotos(dayPhotoList);
    }

    // makeNewDayBarcode
    public Long makeNewDayBarcode(User tmpUser, BarcodeDateDto barcodeDateDto) throws IOException {
        User user = userService.findUser(tmpUser.getId());

        int intYear = Integer.parseInt(barcodeDateDto.getYear());
        int intMonth = Integer.parseInt(barcodeDateDto.getMonth());

        List<Day> dayList = dayService.findDayIdList(user,intYear,intMonth);
        List<String> allDayPhotos = new ArrayList<>();
        for (Day day : dayList) {
            List<String> photosForDay = dayPhotoService.findDayPhotoUrlList(day);
            allDayPhotos.addAll(photosForDay);
        }

        String barcodeFileName = s3Service.makefileName();
        String barcodeFilePath = s3Config.getBarcodeDir() + barcodeFileName;

        File barcodeFile = barcodeService.makeNewBarcode(allDayPhotos, barcodeFilePath);
        s3Service.putFileToS3(barcodeFile, barcodeFileName, s3Config.getBarcodeDir());

        // 해당 년월의 startDate,endDate 구하기
        LocalDate firstDayOfMonth = LocalDate.of(intYear, intMonth, 1);
        LocalDate lastDayOfMonth = firstDayOfMonth.with(TemporalAdjusters.lastDayOfMonth());

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        String startDate = firstDayOfMonth.format(formatter);
        String endDate = lastDayOfMonth.format(formatter);

        Barcode barcode = barcodeService.saveBarcode(
                barcodeFilePath,
                barcodeDateDto.getYear()+"년 "+barcodeDateDto.getMonth()+"월",
                startDate,
                endDate,
                BarcodeType.DAY,
                null);
        userBarcodeService.makeUserDayBarcode(user, barcode);
        return barcode.getId();
    }
}
